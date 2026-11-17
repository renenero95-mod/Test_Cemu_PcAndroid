#pragma once
// Windowed JIT queue drain. All the scheduling logic lives here (and is unit-tested with a fake engine);
// PPCRecompiler.cpp only supplies the few engine hooks.
//
// Hooks (engine side):
//   bool   StopRequested();
//   size_t PopWindow(std::vector<uint32_t>& out, size_t maxCount); // pops up to maxCount still-queued addresses of ONE
//                                                                   // priority class (high first), arrival order; 0 = queue empty
//   bool   StillQueued(uint32_t addr);   // address still waiting (not invalidated / compiled in the meantime)
//   void   Compile(uint32_t addr);
//   bool   Compiled(uint32_t addr);      // the compile produced active code
//   double NowMs();                      // monotonic clock
//   bool   Features(uint32_t addr, CodeFeatures& out);
// Sink: void OnWindow(const std::vector<WindowEntry>&, double planMs)
#include "NeuroJitAdvisor.h"

namespace NeuroJit
{
	inline bool IsPermutation(const std::vector<uint32_t>& order, size_t n)
	{
		if (order.size() != n)
			return false;
		std::vector<uint8_t> seen(n, 0);
		for (uint32_t v : order)
		{
			if (v >= n || seen[v])
				return false;
			seen[v] = 1;
		}
		return true;
	}

	struct NullSink
	{
		void OnWindow(const std::vector<WindowEntry>&, double) {}
	};

	template<class Hooks, class Sink>
	void DrainQueue(Hooks& h, Advisor& adv, Sink& sink)
	{
		std::vector<uint32_t> addrs, order;
		std::vector<WindowEntry> entries;
		addrs.reserve(adv.Window());
		order.reserve(adv.Window());
		entries.reserve(adv.Window());
		while (true)
		{
			if (h.StopRequested())
				return;
			addrs.clear();
			if (h.PopWindow(addrs, adv.Window()) == 0)
			{
				adv.EndBurst(); // queue is empty: the windows seen so far belonged to one burst
				return;
			}
			const Neural::Mode mode = adv.GetMode();
			entries.assign(addrs.size(), WindowEntry());
			const double p0 = h.NowMs();
			for (size_t i = 0; i < addrs.size(); i++)
			{
				entries[i].addr = addrs[i];
				if (mode != Neural::Mode::Off)
				{
					if (!h.Features(addrs[i], entries[i].f))
						entries[i].f = CodeFeatures();
					adv.Predict(entries[i]);
				}
			}
			order.clear();
			if (mode == Neural::Mode::Active && entries.size() >= 2)
				Advisor::PredictedOrder(entries, order);
			if (!IsPermutation(order, entries.size())) // also covers "not reordering": plain arrival order
			{
				order.resize(entries.size());
				for (uint32_t i = 0; i < order.size(); i++)
					order[i] = i;
			}
			const double planMs = h.NowMs() - p0;
			for (uint32_t idx : order)
			{
				if (h.StopRequested())
					return;
				WindowEntry& e = entries[idx];
				if (!h.StillQueued(e.addr))
					continue;
				const double t0 = h.NowMs();
				h.Compile(e.addr);
				const double t1 = h.NowMs();
				if (h.Compiled(e.addr))
				{
					e.compiled = true;
					e.durMs = (float)(t1 - t0);
				}
			}
			if (mode != Neural::Mode::Off)
			{
				adv.FinishWindow(entries, planMs);
				adv.LearnWindow(entries); // after scoring: the score must come from the model as it was when it made the prediction
				sink.OnWindow(entries, planMs);
			}
		}
	}

	template<class Hooks>
	void DrainQueue(Hooks& h, Advisor& adv)
	{
		NullSink sink;
		DrainQueue(h, adv, sink);
	}
}
