#pragma once
// SYNTHETIC workloads used by the trainer of the default weights and by the tests.
// These are NOT measurements of real games: they exist to prove the machinery (learning, guards, scheduling) works,
// including in regimes where the built-in heuristic is wrong.
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuroJitFeatures.h"
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuralMLP.h"
#include <cmath>

namespace NeuroJitSynth
{
	using NeuroJit::CodeFeatures;
	using namespace NeuroJit;

	// random function with plausible structure (log-uniform size, branch density, rare indirect/fp-heavy code)
	inline CodeFeatures RandomFeatures(Neural::Rng& rng)
	{
		CodeFeatures f;
		const float words = std::exp(rng.NextRange(std::log(4.0f), std::log(4096.0f)));
		f.v[FEAT_WORDS] = std::floor(words);
		f.v[FEAT_COND] = std::floor(words * rng.NextRange(0.02f, 0.18f));
		f.v[FEAT_UNCOND] = std::floor(words * rng.NextRange(0.0f, 0.04f));
		f.v[FEAT_CALLS] = std::floor(words * rng.NextRange(0.0f, 0.06f));
		f.v[FEAT_BACK] = std::floor(f.v[FEAT_COND] * rng.NextRange(0.0f, 0.3f));
		const float fpHeavy = rng.NextFloat() < 0.15f ? rng.NextRange(0.3f, 0.7f) : rng.NextRange(0.0f, 0.1f);
		f.v[FEAT_FP] = std::floor(words * fpHeavy);
		f.v[FEAT_LOAD] = std::floor(words * rng.NextRange(0.05f, 0.3f));
		f.v[FEAT_STORE] = std::floor(words * rng.NextRange(0.02f, 0.15f));
		f.v[FEAT_INDIRECT] = rng.NextFloat() < 0.1f ? std::floor(rng.NextRange(1.0f, 6.0f)) : 0.0f;
		f.v[FEAT_BLR] = std::floor(rng.NextRange(1.0f, 4.0f));
		f.v[FEAT_SPAN] = std::floor(words * rng.NextRange(0.3f, 1.0f));
		f.v[FEAT_ALU] = std::max(0.0f, words - f.v[FEAT_COND] - f.v[FEAT_UNCOND] - f.v[FEAT_CALLS] - f.v[FEAT_LOAD] - f.v[FEAT_STORE] - f.v[FEAT_FP]);
		f.valid = true;
		return f;
	}

	enum class Regime
	{
		Baseline,   // roughly what the built-in heuristic assumes
		Device,     // a different device: 3x slower, steeper growth, FP/indirect/loop heavy code costs much more
		Inverted,   // adversarial: big functions are CHEAPER than small ones (the prior is exactly wrong)
		Random,     // cost unrelated to anything the model can see
		Slow100,    // same shape as Baseline but 100x slower (unoptimised build / very slow device)
		Spiky       // cost driven by loops and indirect jumps, not by size: the size heuristic is a poor guide
	};

	// "true" compile time in ms for a function (noise-free part)
	inline float TrueMs(const CodeFeatures& f, Regime r)
	{
		const float w = std::max(f.v[FEAT_WORDS], 1.0f);
		switch (r)
		{
		case Regime::Baseline:
			return 0.05f + 0.006f * std::pow(w, 1.1f) + 0.004f * f.v[FEAT_COND] + 0.01f * f.v[FEAT_INDIRECT];
		case Regime::Device:
			return 0.15f + 0.010f * std::pow(w, 1.25f) + 0.02f * f.v[FEAT_BACK] * std::pow(w, 0.3f) + 0.012f * f.v[FEAT_FP] + 0.8f * f.v[FEAT_INDIRECT];
		case Regime::Inverted:
			return 0.05f + 40.0f / (1.0f + 0.01f * w);
		case Regime::Random:
			return 1.0f;
		case Regime::Spiky:
			return 0.05f + 0.003f * w + 0.5f * f.v[FEAT_BACK] + 15.0f * f.v[FEAT_INDIRECT] + 0.01f * f.v[FEAT_FP];
		case Regime::Slow100:
			return 100.0f * (0.05f + 0.006f * std::pow(w, 1.1f) + 0.004f * f.v[FEAT_COND] + 0.01f * f.v[FEAT_INDIRECT]);
		}
		return 1.0f;
	}

	// measured time: true time times log-normal noise (sigma in ln units)
	inline float MeasuredMs(const CodeFeatures& f, Regime r, Neural::Rng& rng, float sigma = 0.25f)
	{
		float ms = TrueMs(f, r);
		if (r == Regime::Random)
			return std::exp(rng.NextRange(std::log(0.1f), std::log(30.0f)));
		const float u1 = std::max(rng.NextFloat(), 1e-7f), u2 = rng.NextFloat();
		const float g = std::sqrt(-2.0f * std::log(u1)) * std::cos(6.2831853f * u2);
		return ms * std::exp(sigma * g);
	}
}
