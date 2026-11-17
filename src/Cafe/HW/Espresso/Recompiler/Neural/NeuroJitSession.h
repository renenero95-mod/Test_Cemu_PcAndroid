#pragma once
// File side of the advisor: loads/saves the learned weights, reads the optional mode switch, writes a human readable
// report and a small trace of (features, measured compile time) that can be used to train better weights offline.
//   <dir>/jit_cost.weights     learned weights (atomic write; a damaged file is ignored and the built-in priors are used)
//   <dir>/mode.txt             optional: off | shadow | active | auto   (re-read every report interval)
//   <dir>/report.txt           what the advisor is doing and how well
//   <dir>/jit_cost_trace.csv   features + measured ms (capped in size)
// Every file operation uses error codes: no exceptions, and a failure only disables that file feature.
#include "NeuroJitScheduler.h"
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>

namespace NeuroJit
{
	struct SessionConfig
	{
		double reportIntervalSec = 30.0;
		double saveIntervalSec = 60.0;
		uint64_t saveMinObservations = 256;
		uint64_t traceCapBytes = 2u * 1024u * 1024u;
	};

	inline bool ReadSmallFile(const std::filesystem::path& p, std::string& out, size_t maxBytes)
	{
		std::error_code ec;
		const auto sz = std::filesystem::file_size(p, ec);
		if (ec || sz > maxBytes)
			return false;
		std::ifstream in(p, std::ios::binary);
		if (!in)
			return false;
		out.assign((size_t)sz, '\0');
		in.read(out.data(), (std::streamsize)sz);
		return (size_t)in.gcount() == (size_t)sz;
	}

	inline bool AtomicWriteFile(const std::filesystem::path& p, const void* data, size_t size)
	{
		std::error_code ec;
		std::filesystem::path tmp = p;
		tmp += ".tmp";
		{
			std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
			if (!out)
				return false;
			out.write((const char*)data, (std::streamsize)size);
			out.flush();
			if (!out)
				return false;
		}
		std::filesystem::rename(tmp, p, ec);
		if (ec)
		{
			std::filesystem::remove(tmp, ec);
			return false;
		}
		return true;
	}

	// "off" | "shadow" | "active" | "auto" (case-insensitive, surrounding whitespace ignored). Anything else: not recognised.
	// Optional second word: the window size (number of functions the advisor may reorder at once), e.g. "active 2048".
	// A missing or invalid number is ignored (windowOut stays -1); a valid one is clamped to [kMinWindow, kMaxWindowLimit] by the advisor.
	inline bool ParseModeText(const std::string& text, int& modeOut /* 0 off, 1 shadow, 2 active, 3 auto */, int* windowOut = nullptr)
	{
		if (windowOut)
			*windowOut = -1;
		std::string t, num;
		size_t pos = 0;
		for (; pos < text.size(); pos++)
		{
			const char c = text[pos];
			if (c == ' ' || c == '\t' || c == '\r' || c == '\n')
			{
				if (!t.empty())
					break;
				continue;
			}
			t.push_back((char)((c >= 'A' && c <= 'Z') ? c - 'A' + 'a' : c));
			if (t.size() > 16)
				return false;
		}
		while (pos < text.size() && (text[pos] == ' ' || text[pos] == '\t'))
			pos++;
		for (; pos < text.size() && text[pos] >= '0' && text[pos] <= '9' && num.size() < 8; pos++)
			num.push_back(text[pos]);
		const bool numberEndsCleanly = pos >= text.size() || text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\r' || text[pos] == '\n';
		if (windowOut && !num.empty() && numberEndsCleanly)
			*windowOut = (int)std::min<long>(std::stol(num), 1000000L);
		if (t == "off") modeOut = 0;
		else if (t == "shadow") modeOut = 1;
		else if (t == "active") modeOut = 2;
		else if (t == "auto") modeOut = 3;
		else return false;
		return true;
	}

	class Session
	{
	public:
		Session(Advisor& adv, const std::filesystem::path& dir, const SessionConfig& cfg = SessionConfig())
			: m_adv(adv), m_dir(dir), m_cfg(cfg)
		{
			std::error_code ec;
			std::filesystem::create_directories(m_dir, ec);
			m_dirOk = !ec;
			std::string blob;
			if (m_dirOk && ReadSmallFile(Weights(), blob, 8192))
				m_loadedFromFile = m_adv.LoadWeights((const uint8_t*)blob.data(), blob.size());
			ReadModeFile();
			m_last = Clock::now();
			m_lastSave = m_last;
		}

		~Session() { Flush(); }

		bool LoadedFromFile() const { return m_loadedFromFile; }

		void OnWindow(const std::vector<WindowEntry>& entries, double)
		{
			if (!m_dirOk)
				return;
			WriteTrace(entries);
			const auto now = Clock::now();
			const double sinceReport = std::chrono::duration<double>(now - m_last).count();
			if (sinceReport >= m_cfg.reportIntervalSec)
			{
				m_last = now;
				ReadModeFile();
				WriteReport();
			}
			const double sinceSave = std::chrono::duration<double>(now - m_lastSave).count();
			if (sinceSave >= m_cfg.saveIntervalSec && m_adv.Stats().observed >= m_savedAtObserved + m_cfg.saveMinObservations)
				SaveWeights();
		}

		void Flush()
		{
			if (!m_dirOk || m_flushed)
				return;
			m_flushed = true;
			if (m_adv.Stats().observed > m_savedAtObserved)
				SaveWeights();
			WriteReport();
		}

		bool SaveWeights()
		{
			m_lastSave = Clock::now();
			const std::vector<uint8_t> blob = m_adv.SerializeModel();
			if (blob.empty() || !AtomicWriteFile(Weights(), blob.data(), blob.size()))
				return false;
			m_savedAtObserved = m_adv.Stats().observed;
			return true;
		}

		void WriteReport()
		{
			const std::string r = m_adv.BuildReport() + (m_loadedFromFile ? "weights: loaded from file at start\n" : "weights: built-in priors at start\n");
			AtomicWriteFile(m_dir / "report.txt", r.data(), r.size());
		}

		void ReadModeFile()
		{
			std::string text;
			int mode = -1, window = -1;
			if (!m_dirOk || !ReadSmallFile(m_dir / "mode.txt", text, 64) || !ParseModeText(text, mode, &window))
				return;
			if (window > 0 && window != m_lastWindowFile)
			{
				m_lastWindowFile = window;
				m_adv.SetWindow((uint32_t)window);
			}
			if (mode == m_lastModeFile)
				return;
			m_lastModeFile = mode;
			if (mode == 0) m_adv.ForceMode(Neural::Mode::Off);
			else if (mode == 1) m_adv.ForceMode(Neural::Mode::Shadow);
			else if (mode == 2) m_adv.ForceMode(Neural::Mode::Active);
			else m_adv.SetAuto();
		}

	private:
		using Clock = std::chrono::steady_clock;
		std::filesystem::path Weights() const { return m_dir / "jit_cost.weights"; }

		void WriteTrace(const std::vector<WindowEntry>& entries)
		{
			if (m_traceBytes >= m_cfg.traceCapBytes)
				return;
			const std::filesystem::path p = m_dir / "jit_cost_trace.csv";
			std::string out;
			if (!m_traceHeader)
			{
				std::error_code ec;
				if (!std::filesystem::exists(p, ec))
					out += "addr,words,cond,uncond,calls,back,fp,load,store,indirect,blr,span,alu,ms,pred_ms\n";
				m_traceHeader = true;
			}
			char line[256];
			for (const auto& e : entries)
			{
				if (!e.compiled || !e.f.valid)
					continue;
				int n = std::snprintf(line, sizeof(line), "%08X", e.addr);
				for (int i = 0; i < kFeatureCount && n > 0 && n < (int)sizeof(line) - 16; i++)
					n += std::snprintf(line + n, sizeof(line) - n, ",%.0f", e.f.v[i]);
				n += std::snprintf(line + n, sizeof(line) - n, ",%.4f,%.4f\n", e.durMs, std::exp(e.predLogMs));
				if (n > 0 && n < (int)sizeof(line))
					out.append(line, (size_t)n);
			}
			if (out.empty())
				return;
			std::ofstream f(p, std::ios::app | std::ios::binary);
			if (!f)
				return;
			f.write(out.data(), (std::streamsize)out.size());
			m_traceBytes += out.size();
		}

		Advisor& m_adv;
		std::filesystem::path m_dir;
		SessionConfig m_cfg;
		bool m_dirOk = false, m_loadedFromFile = false, m_flushed = false, m_traceHeader = false;
		int m_lastModeFile = -1, m_lastWindowFile = -1;
		uint64_t m_savedAtObserved = 0, m_traceBytes = 0;
		Clock::time_point m_last, m_lastSave;
	};
}
