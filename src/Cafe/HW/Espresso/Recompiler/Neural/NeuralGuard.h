#pragma once
// Guards that decide how much a learned model is allowed to influence the emulator.
// A model starts in SHADOW mode (it only predicts and is scored against what really happened) and is promoted to
// ACTIVE only after it has measurably beaten the plain baseline; it is demoted again as soon as it stops doing so.
#include "NeuralMLP.h"

namespace Neural
{
	enum class Mode : uint8_t { Off = 0, Shadow = 1, Active = 2 };

	inline const char* ModeName(Mode m)
	{
		switch (m)
		{
		case Mode::Off: return "off";
		case Mode::Shadow: return "shadow";
		case Mode::Active: return "active";
		}
		return "?";
	}

	struct Ema
	{
		float value = 0.0f;
		uint32_t count = 0;
		void Add(float x, float alpha)
		{
			if (!IsFiniteF(x))
				return;
			value = (count == 0) ? x : value + alpha * (x - value);
			count++;
		}
	};

	struct GateConfig
	{
		float promoteGain = 0.03f;      // the model must be better than baseline by at least this much ...
		float promoteZ = 3.0f;          // ... even after subtracting this many standard errors (statistical confidence)
		float demoteZ = 1.0f;           // control is lost when mean + demoteZ * standard error <= 0 (cannot claim to help any more)
		uint32_t minWindowsPromote = 5; // evaluated windows needed before a promotion is even considered
		uint32_t minWindowsDemote = 3;
		uint32_t dwellWindows = 4;      // minimum evaluations between two mode changes (anti flapping)
	};

	// Gate fed with one "gain over baseline" number per evaluated unit of work (+1 = ideal, 0 = equal to baseline, <0 = worse).
	// Decisions use the mean and the standard error of the LAST kRing evaluations, so a lucky/unlucky streak does not flip it.
	class AuthorityGate
	{
	public:
		static constexpr uint32_t kRing = 32;

		explicit AuthorityGate(const GateConfig& cfg = GateConfig()) : m_cfg(cfg) {}

		Mode GetMode() const { return m_mode; }
		bool IsAuto() const { return m_auto; }
		uint32_t Evaluations() const { return m_total; }
		uint32_t Transitions() const { return m_transitions; }
		float GainMean() const { return m_mean; }
		float GainStdErr() const { return m_se; }

		// user override (e.g. from a config file): the gate stops changing the mode by itself
		void Force(Mode m) { m_mode = m; m_auto = false; }
		void SetAuto(Mode startMode) { m_mode = startMode; m_auto = true; m_sinceChange = 0; }

		void Feed(float gain)
		{
			if (!IsFiniteF(gain))
				return;
			gain = gain < -1.0f ? -1.0f : (gain > 1.0f ? 1.0f : gain);
			m_ring[m_total % kRing] = gain;
			m_total++;
			m_sinceChange++;
			const uint32_t n = m_total < kRing ? m_total : kRing;
			float sum = 0.0f;
			for (uint32_t i = 0; i < n; i++)
				sum += m_ring[i];
			m_mean = sum / (float)n;
			float sq = 0.0f;
			for (uint32_t i = 0; i < n; i++)
				sq += (m_ring[i] - m_mean) * (m_ring[i] - m_mean);
			m_se = n > 1 ? std::sqrt(sq / (float)(n - 1) / (float)n) : 1.0f; // unknown spread => maximal uncertainty
			if (!m_auto || m_mode == Mode::Off || m_sinceChange < m_cfg.dwellWindows)
				return;
			if (m_mode == Mode::Shadow && m_total >= m_cfg.minWindowsPromote && m_mean - m_cfg.promoteZ * m_se >= m_cfg.promoteGain)
				Change(Mode::Active);
			else if (m_mode == Mode::Active && m_total >= m_cfg.minWindowsDemote && m_mean + m_cfg.demoteZ * m_se <= 0.0f)
				Change(Mode::Shadow);
		}

	private:
		void Change(Mode m) { m_mode = m; m_sinceChange = 0; m_transitions++; }

		GateConfig m_cfg;
		Mode m_mode = Mode::Shadow;
		bool m_auto = true;
		float m_ring[kRing] = {};
		float m_mean = 0.0f, m_se = 1.0f;
		uint32_t m_total = 0, m_sinceChange = 0, m_transitions = 0;
	};
}
