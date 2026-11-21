#pragma once
// NeuroJit advisor: a small neural network that predicts how long a PPC function will take to JIT-compile, and uses that
// to order the JIT work queue (shortest-job-first minimises the average time functions wait in the slow interpreter).
//
// SAFETY MODEL - the network can only ever change the ORDER in which independent compile jobs run:
//  * what is compiled, and how, never depends on the network; a wrong prediction costs speed, never correctness
//  * the order is bounded to a window of kMaxWindow jobs (nothing can starve: a job waits at most for the window it is in)
//    and is validated to be a permutation
//  * the network is scored against reality on every window (counterfactual: FIFO vs predicted order vs perfect order)
//    and only gets control after beating FIFO; it loses control again when it stops doing so (AuthorityGate)
//  * learning is clipped (Huber loss, gradient clipping, outlier rejection) and rolled back on divergence
//  * predictions are bounded around a calibrated hand-written heuristic
//  * learning happens at the end of a window, in shuffled order (the executed order is chosen by the model itself and is not iid)
// Single-threaded by design: use it from the JIT thread only. It keeps no global state.
#include "NeuralGuard.h"
#include "NeuroJitFeatures.h"
#include "NeuroJitDefaultWeights.h"
#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

namespace NeuroJit
{
	constexpr uint32_t kModelId = 0x4354494Au; // "JITC"
	constexpr uint32_t kDefaultWindow = 512;   // jobs per window: bounds how far any job can be delayed (no starvation)
	constexpr uint32_t kMinWindow = 16, kMaxWindowLimit = 8192;
	constexpr int kWindowMultiples = 4;        // the advisor also reports what 1x, 2x, 4x and 8x the current window would have achieved
	constexpr uint32_t kMinEvalEntries = 16;   // windows with fewer compiled jobs say too little to judge the model
	constexpr float kLogMsMin = -5.0f, kLogMsMax = 10.0f;

	struct WindowEntry
	{
		uint32_t addr = 0;
		CodeFeatures f;
		float predLogMs = 0.0f;
		float durMs = 0.0f;     // measured compile time (valid if compiled)
		bool compiled = false;  // produced active code (only these are used for learning/scoring)
	};

	struct AdvisorConfig
	{
		bool useModel = true;               // false = order by the hand-written heuristic only (control experiment / kill switch for the network)
		float lr0 = 0.02f, lrMin = 0.002f;     // measured with tools/neural/tune.cpp: 5x faster time-to-benefit than 0.004, no instability
		float huberDelta = 1.0f, clipNorm = 5.0f, weightDecay = 1.0e-6f;
		float maxOutlierLogErr = 6.0f;      // |target - prediction| above this => sample ignored
		float maxDevFromBaseline = 6.0f;    // prediction is kept within +-this (ln units, ~400x) of the calibrated heuristic; 3.0 capped the gain at 28% instead of 41% when the heuristic is badly wrong
		uint32_t snapshotEvery = 256;
		uint32_t divergeStreak = 32;
		float planBudgetMsPerEntry = 0.25f; // if planning is slower than this on average, the advisor switches itself off
		Neural::GateConfig gate;
	};

	// what a window k times larger would have achieved on the compiles that really happened (counterfactual, from measured durations)
	struct WindowSizeStats
	{
		Neural::Ema gain;           // share of the whole burst's waiting time that would have been saved (whole queue, offsets included)
		Neural::Ema worstDelayMs;   // slowest-case: how much later the most unlucky single function finishes than under FIFO
		float maxDelayMs = 0.0f;
		uint32_t groups = 0;
	};

	struct GroupResult { double tFifo = 0.0, tPred = 0.0, worstDelayMs = 0.0; };

	struct AdvisorStats
	{
		WindowSizeStats windowStats[kWindowMultiples];
		uint64_t observed = 0, skippedOutliers = 0, rollbacks = 0, windows = 0, compiles = 0;
		Neural::Ema gainVsFifo;    // (T_fifo - T_predicted) / T_fifo, averaged over windows
		Neural::Ema oracleGap;     // (T_predicted - T_best) / T_fifo
		Neural::Ema absErrLn;      // |ln(actual) - ln(predicted)|
		Neural::Ema planMsPerEntry;
		bool modelLoaded = false;
		const char* disabledReason = "";
	};

	class Advisor
	{
	public:
		explicit Advisor(const AdvisorConfig& cfg = AdvisorConfig()) : m_cfg(cfg), m_gate(cfg.gate)
		{
			m_modelOk = LoadWeights(kDefaultWeights, kDefaultWeightsSize);
			m_stats.modelLoaded = m_modelOk;
		}

		// ---- model ---------------------------------------------------------------------
		bool LoadWeights(const uint8_t* data, size_t size)
		{
			const int dims[4] = { kFeatureCount, 16, 8, 1 };
			Neural::MLP tmp;
			if (!tmp.Deserialize(data, size, kModelId, dims, 3))
				return false;
			m_model = tmp;
			m_snapshot = tmp;
			m_modelOk = true;
			m_stats.modelLoaded = true;
			return true;
		}

		std::vector<uint8_t> SerializeModel() const
		{
			std::vector<uint8_t> out(m_model.SerializedSize());
			if (!m_model.Serialize(out.data(), out.size(), kModelId))
				out.clear();
			return out;
		}

		// last known-good copy of the model (the one a rollback restores); for diagnostics and tests
		std::vector<uint8_t> SerializeSnapshot() const
		{
			std::vector<uint8_t> out(m_snapshot.SerializedSize());
			if (!m_snapshot.Serialize(out.data(), out.size(), kModelId))
				out.clear();
			return out;
		}

		// ln(ms), bounded and always finite
		float PredictLogMs(const CodeFeatures& f) const
		{
			const float h = HeuristicLogMs(f) + m_calib.value;
			if (!m_modelOk || !m_cfg.useModel)
				return Clamp(h);
			float x[kFeatureCount], o[1] = { 0.0f };
			NormalizeFeatures(f, x);
			m_model.Forward(x, o);
			float p = Neural::SanitizeFloat(o[0], kLogMsMin, kLogMsMax, h);
			p = std::min(std::max(p, h - m_cfg.maxDevFromBaseline), h + m_cfg.maxDevFromBaseline);
			return Clamp(p);
		}

		void Predict(WindowEntry& e) const
		{
			e.predLogMs = e.f.valid ? PredictLogMs(e.f) : 0.0f; // unknown functions stay in the middle (stable order)
		}

		// online learning from one real compile
		void Observe(const CodeFeatures& f, float ms)
		{
			if (!f.valid || !Neural::IsFiniteF(ms) || ms <= 0.0f || m_gate.GetMode() == Neural::Mode::Off || !m_modelOk || !m_cfg.useModel)
				return;
			const float target = Clamp(std::log(std::max(ms, 0.0067f)));
			float x[kFeatureCount], o[1] = { 0.0f };
			NormalizeFeatures(f, x);
			m_model.Forward(x, o);
			const float err = target - Neural::SanitizeFloat(o[0], kLogMsMin, kLogMsMax, 0.0f);
			m_calib.Add(target - HeuristicLogMs(f), 0.02f);
			if (std::fabs(err) > m_cfg.maxOutlierLogErr)
			{
				m_stats.skippedOutliers++;
				return;
			}
			const float lr = std::max(m_cfg.lrMin, m_cfg.lr0 / (1.0f + (float)m_stats.observed / 5000.0f));
			const float loss = m_model.TrainStep(x, &target, lr, m_cfg.huberDelta, m_cfg.clipNorm, m_cfg.weightDecay);
			m_stats.observed++;
			m_fastLoss.Add(loss, 0.1f);
			m_slowLoss.Add(loss, 0.005f);
			const bool broken = !m_model.WeightsValid() || !Neural::IsFiniteF(loss) || loss >= 1.0e5f;
			if (m_fastLoss.value > 4.0f * m_slowLoss.value + 0.5f)
				m_divergeCount++;
			else
				m_divergeCount = 0;
			if (broken || m_divergeCount >= m_cfg.divergeStreak)
			{
				m_model = m_snapshot; // last known good
				m_stats.rollbacks++;
				m_divergeCount = 0;
				m_fastLoss = m_slowLoss;
			}
			else if (m_stats.observed % m_cfg.snapshotEvery == 0 && m_fastLoss.value <= 1.5f * m_slowLoss.value + 0.1f)
				m_snapshot = m_model;
		}

		// Learn from a finished window. The jobs were EXECUTED in an order chosen by the model itself (cheapest first), so training
		// on them in that order would feed the network long runs of similar samples and make it forget everything else (we measured the
		// gain decaying from +22% to ~1%). They are therefore learned in a deterministic pseudo-random order.
		void LearnWindow(const std::vector<WindowEntry>& e)
		{
			m_learnOrder.clear();
			for (uint32_t i = 0; i < e.size(); i++)
				if (e[i].compiled && e[i].f.valid)
					m_learnOrder.push_back(i);
			for (size_t i = m_learnOrder.size(); i > 1; i--) // Fisher-Yates
				std::swap(m_learnOrder[i - 1], m_learnOrder[m_rng.NextU64() % i]);
			for (uint32_t idx : m_learnOrder)
				Observe(e[idx].f, e[idx].durMs);
		}

		// ---- window size -----------------------------------------------------------------
		uint32_t Window() const { return m_window; }
		void SetWindow(uint32_t n)
		{
			n = n < kMinWindow ? kMinWindow : (n > kMaxWindowLimit ? kMaxWindowLimit : n);
			if (n == m_window)
				return;
			m_window = n;
			for (auto& w : m_stats.windowStats)
				w = WindowSizeStats();
			m_burstPred.clear();
			m_burstDur.clear();
			m_burstOffsetMs = 0.0;
			for (int m = 0; m < kWindowMultiples; m++)
			{
				m_burstSavings[m] = m_burstFifoGlobal[m] = 0.0;
				m_burstWorst[m] = 0.0f;
				m_burstGroups[m] = 0;
			}
		}

		// Sum of completion times with the jobs run in arrival order (T_fifo) and in predicted order (T_pred) and the worst
		// delay one single job suffers compared with arrival order. pred/dur are for the same n jobs, in arrival order.
		static GroupResult EvaluateGroup(const float* pred, const float* dur, size_t n)
		{
			GroupResult r;
			if (n == 0)
				return r;
			std::vector<double> compFifo(n), compPred(n);
			double t = 0.0;
			for (size_t i = 0; i < n; i++)
			{
				t += dur[i];
				compFifo[i] = t;
				r.tFifo += t;
			}
			std::vector<uint32_t> order(n);
			for (uint32_t i = 0; i < n; i++)
				order[i] = i;
			std::stable_sort(order.begin(), order.end(), [&](uint32_t a, uint32_t b) { return pred[a] < pred[b]; });
			t = 0.0;
			for (uint32_t idx : order)
			{
				t += dur[idx];
				compPred[idx] = t;
				r.tPred += t;
				r.worstDelayMs = std::max(r.worstDelayMs, t - compFifo[idx]);
			}
			return r;
		}

		// The queue emptied: score the windows collected during this burst with groups of 1/2/4/8 windows, then start afresh.
		void EndBurst()
		{
			EvaluateChunk();
			CommitBurst();
		}

		// ---- scheduling ----------------------------------------------------------------
		Neural::Mode GetMode() const { return m_gate.GetMode(); }
		void ForceMode(Neural::Mode m) { m_gate.Force(m); }
		void SetAuto() { m_gate.SetAuto(Neural::Mode::Shadow); }
		bool ShouldReorder() const { return m_gate.GetMode() == Neural::Mode::Active; }

		// indices of e sorted by predicted cost, cheapest first (stable). Always a valid permutation.
		static void PredictedOrder(const std::vector<WindowEntry>& e, std::vector<uint32_t>& order)
		{
			order.resize(e.size());
			for (uint32_t i = 0; i < order.size(); i++)
				order[i] = i;
			std::stable_sort(order.begin(), order.end(), [&](uint32_t a, uint32_t b) { return e[a].predLogMs < e[b].predLogMs; });
		}

		// sum over jobs of their completion time, running them in `order` (only compiled jobs count)
		static double TotalCompletion(const std::vector<WindowEntry>& e, const std::vector<uint32_t>& order)
		{
			double t = 0.0, sum = 0.0;
			for (uint32_t idx : order)
			{
				if (!e[idx].compiled)
					continue;
				t += e[idx].durMs;
				sum += t;
			}
			return sum;
		}

		// Counterfactual scoring of a finished window (entries in ARRIVAL order with measured durations).
		void FinishWindow(const std::vector<WindowEntry>& e, double planMs)
		{
			m_stats.windows++;
			uint32_t valid = 0;
			for (auto& x : e)
				if (x.compiled)
				{
					valid++;
					m_stats.compiles++;
					if (x.f.valid)
						m_stats.absErrLn.Add(std::fabs(std::log(std::max(x.durMs, 0.0067f)) - x.predLogMs), 0.02f);
				}
			if (!e.empty())
			{
				m_stats.planMsPerEntry.Add((float)(planMs / (double)e.size()), 0.1f);
				if (m_stats.planMsPerEntry.count >= 32 && m_stats.planMsPerEntry.value > m_cfg.planBudgetMsPerEntry && m_gate.GetMode() != Neural::Mode::Off)
				{
					m_gate.Force(Neural::Mode::Off);
					m_stats.disabledReason = "planning too slow";
				}
			}
			if (valid < kMinEvalEntries)
				return;
			std::vector<uint32_t> fifo(e.size()), pred, best;
			for (uint32_t i = 0; i < fifo.size(); i++)
				fifo[i] = i;
			PredictedOrder(e, pred);
			best = fifo;
			std::stable_sort(best.begin(), best.end(), [&](uint32_t a, uint32_t b) { return e[a].durMs < e[b].durMs; });
			const double tFifo = TotalCompletion(e, fifo), tPred = TotalCompletion(e, pred), tBest = TotalCompletion(e, best);
			if (!(tFifo > 0.0))
				return;
			RecordForGroups(e);
			const float gain = (float)((tFifo - tPred) / tFifo);
			m_stats.gainVsFifo.Add(gain, 0.05f);
			m_stats.oracleGap.Add((float)((tPred - tBest) / tFifo), 0.05f);
			m_gate.Feed(gain);
		}

		const AdvisorStats& Stats() const { return m_stats; }
		const Neural::AuthorityGate& Gate() const { return m_gate; }
		float Calibration() const { return m_calib.value; }

		std::string BuildReport() const
		{
			char buf[2048];
			std::snprintf(buf, sizeof(buf),
				"NeuroJit report\n"
				"mode: %s (%s)%s%s\n"
				"windows evaluated: %llu, compiles observed: %llu, learning steps: %llu\n"
				"gain vs FIFO (last windows: mean %+.1f%% +-%.1f%%, EMA %+.1f%%)   gap to perfect order: %.1f%%\n"
				"prediction error (EMA |ln actual - ln predicted|): %.3f\n"
				"model calibration offset vs heuristic (ln): %+.2f\n"
				"outliers skipped: %llu, rollbacks: %llu, mode changes: %u\n"
				"planning cost: %.4f ms per queued function\n"
				"%s",
				Neural::ModeName(m_gate.GetMode()), m_gate.IsAuto() ? "automatic" : "forced",
				m_stats.disabledReason[0] ? ", disabled: " : "", m_stats.disabledReason,
				(unsigned long long)m_stats.windows, (unsigned long long)m_stats.compiles, (unsigned long long)m_stats.observed,
				100.0 * m_gate.GainMean(), 100.0 * m_gate.GainStdErr(), 100.0 * m_stats.gainVsFifo.value, 100.0 * m_stats.oracleGap.value,
				m_stats.absErrLn.value, m_calib.value,
				(unsigned long long)m_stats.skippedOutliers, (unsigned long long)m_stats.rollbacks, m_gate.Transitions(),
				m_stats.planMsPerEntry.value, WindowReport().c_str());
			return buf;
		}

		// "what if the window were bigger?" from the compiles that really happened
		std::string WindowReport() const
		{
			std::string out = "window size now " + std::to_string(m_window) + " functions. If it were:\n";
			char line[200];
			for (int m = 0; m < kWindowMultiples; m++)
			{
				const WindowSizeStats& s = m_stats.windowStats[m];
				if (s.groups == 0)
					continue;
				std::snprintf(line, sizeof(line), "  %5u -> would have saved %.1f%% of the total waiting time of a burst; worst delay of one function %.0f ms (average of worst cases %.0f); %u groups measured\n",
					m_window << m, 100.0 * s.gain.value, (double)s.maxDelayMs, s.worstDelayMs.value, s.groups);
				out += line;
			}
			return out;
		}

	private:
		void RecordForGroups(const std::vector<WindowEntry>& e)
		{
			std::vector<float> pred, dur;
			for (auto& x : e)
				if (x.compiled)
				{
					pred.push_back(x.predLogMs);
					dur.push_back(x.durMs);
				}
			m_burstPred.push_back(std::move(pred));
			m_burstDur.push_back(std::move(dur));
			if (m_burstPred.size() >= (size_t)(1 << (kWindowMultiples - 1)))
				EvaluateChunk();
		}

		// Evaluate the collected windows with groups of 1/2/4/8 windows on the same prefix. The savings of a group do not depend on
		// when the group starts, but the time every job of the burst waited does, so the offset (work done before the group in this
		// burst) is added to the baseline: that gives the gain over the WHOLE queue, which is what a bigger window really buys.
		void EvaluateChunk()
		{
			const size_t n = m_burstPred.size();
			if (n == 0)
				return;
			std::vector<double> winTotal(n, 0.0);
			double chunkTotal = 0.0;
			for (size_t w = 0; w < n; w++)
			{
				for (float d : m_burstDur[w])
					winTotal[w] += d;
				chunkTotal += winTotal[w];
			}
			size_t kmax = 1;
			while (kmax * 2 <= n && kmax * 2 <= ((size_t)1 << (kWindowMultiples - 1)))
				kmax *= 2;
			const size_t covered = n - n % kmax;
			for (int m = 0; m < kWindowMultiples; m++)
			{
				const size_t k = (size_t)1 << m;
				if (k > kmax)
					break;
				for (size_t g = 0; g + k <= covered; g += k)
				{
					std::vector<float> pred, dur;
					double offset = m_burstOffsetMs;
					for (size_t w = 0; w < g; w++)
						offset += winTotal[w];
					for (size_t w = g; w < g + k; w++)
					{
						pred.insert(pred.end(), m_burstPred[w].begin(), m_burstPred[w].end());
						dur.insert(dur.end(), m_burstDur[w].begin(), m_burstDur[w].end());
					}
					const GroupResult r = EvaluateGroup(pred.data(), dur.data(), pred.size());
					m_burstSavings[m] += r.tFifo - r.tPred;
					m_burstFifoGlobal[m] += r.tFifo + (double)pred.size() * offset;
					m_burstWorst[m] = std::max(m_burstWorst[m], (float)r.worstDelayMs);
					m_burstGroups[m]++;
				}
			}
			m_burstOffsetMs += chunkTotal;
			m_burstPred.clear();
			m_burstDur.clear();
		}

		void CommitBurst()
		{
			for (int m = 0; m < kWindowMultiples; m++)
			{
				if (m_burstFifoGlobal[m] > 0.0)
				{
					WindowSizeStats& s = m_stats.windowStats[m];
					s.gain.Add((float)(m_burstSavings[m] / m_burstFifoGlobal[m]), 0.2f);
					s.worstDelayMs.Add(m_burstWorst[m], 0.2f);
					s.maxDelayMs = std::max(s.maxDelayMs, m_burstWorst[m]);
					s.groups += m_burstGroups[m];
				}
				m_burstSavings[m] = m_burstFifoGlobal[m] = 0.0;
				m_burstWorst[m] = 0.0f;
				m_burstGroups[m] = 0;
			}
			m_burstOffsetMs = 0.0;
		}

		static float Clamp(float v) { return Neural::SanitizeFloat(v, kLogMsMin, kLogMsMax, 0.0f); }

		AdvisorConfig m_cfg;
		Neural::MLP m_model, m_snapshot;
		bool m_modelOk = false;
		Neural::AuthorityGate m_gate;
		AdvisorStats m_stats;
		Neural::Ema m_calib, m_fastLoss, m_slowLoss;
		uint32_t m_divergeCount = 0;
		uint32_t m_window = kDefaultWindow;
		std::vector<std::vector<float>> m_burstPred, m_burstDur; // compiled jobs of the windows of the current chunk, arrival order
		double m_burstOffsetMs = 0.0, m_burstSavings[kWindowMultiples] = {}, m_burstFifoGlobal[kWindowMultiples] = {};
		float m_burstWorst[kWindowMultiples] = {};
		uint32_t m_burstGroups[kWindowMultiples] = {};
		Neural::Rng m_rng{ 0x9E3779B97F4A7C15ull };
		std::vector<uint32_t> m_learnOrder;
	};
}
