import { describe, expect, it } from 'vitest';
import { detectTier } from './tier';

describe('detectTier', () => {
  it('tier lowMemory disables blur', () => {
    const f = detectTier({ deviceMemory: 2, cores: 8 });
    expect(f.tier).toBe('LOW');
    expect(f.liveBlur).toBe(false);
    expect(f.blurScope).toBe('none');
    expect(f.kenBurns).toBe(false);
    expect(f.parallax).toBe(false);
    expect(f.durationScale).toBe(0.6);
  });

  it('4 cores (TV box / A53 phone) is LOW', () => {
    expect(detectTier({ deviceMemory: 4, cores: 4 }).tier).toBe('LOW');
  });

  it('8 GB / 8 cores is HIGH with blur everywhere + Ken Burns', () => {
    const f = detectTier({ deviceMemory: 8, cores: 8 });
    expect(f.tier).toBe('HIGH');
    expect(f.blurScope).toBe('all');
    expect(f.kenBurns).toBe(true);
  });

  it('mid device: blur on bars only, parallax, no Ken Burns', () => {
    const f = detectTier({ deviceMemory: 4, cores: 6 });
    expect(f.tier).toBe('MID');
    expect(f.liveBlur).toBe(true);
    expect(f.blurScope).toBe('bars');
    expect(f.parallax).toBe(true);
    expect(f.kenBurns).toBe(false);
  });

  it('slow frame probe downgrades one tier', () => {
    expect(detectTier({ deviceMemory: 8, cores: 8, avgFrameMs: 28 }).tier).toBe('MID');
    expect(detectTier({ deviceMemory: 4, cores: 6, avgFrameMs: 25 }).tier).toBe('LOW');
    expect(detectTier({ deviceMemory: 8, cores: 8, avgFrameMs: 16.7 }).tier).toBe('HIGH');
  });

  it('tier override wins', () => {
    expect(detectTier({ deviceMemory: 2, cores: 2 }, 'HIGH').tier).toBe('HIGH');
    expect(detectTier({ deviceMemory: 8, cores: 16 }, 'LOW').liveBlur).toBe(false);
    expect(detectTier({ deviceMemory: 8, cores: 16, avgFrameMs: 40 }, 'HIGH').tier).toBe('HIGH');
  });

  it('reduced motion disables parallax/Ken Burns without changing tier', () => {
    const f = detectTier({ deviceMemory: 8, cores: 8, reducedMotion: true });
    expect(f.tier).toBe('HIGH');
    expect(f.kenBurns).toBe(false);
    expect(f.parallax).toBe(false);
  });

  it('unknown signals default to MID', () => {
    expect(detectTier({}).tier).toBe('MID');
  });
});
