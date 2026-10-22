/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Deterministic seeded PRNG using Mulberry32 algorithm.
 */
export class SeededRNG {
  private state: number;

  constructor(seed: number) {
    this.state = Math.floor(seed) >>> 0;
    if (this.state === 0) {
      this.state = 1337;
    }
  }

  /**
   * Returns a pseudorandom float between 0 (inclusive) and 1 (exclusive).
   */
  next(): number {
    let t = (this.state += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  /**
   * Returns an integer between min and max (inclusive).
   */
  nextInt(min: number, max: number): number {
    const f = this.next();
    return Math.floor(f * (max - min + 1)) + min;
  }

  /**
   * Returns a float between min and max.
   */
  nextFloat(min: number, max: number): number {
    return this.next() * (max - min) + min;
  }

  /**
   * Random boolean with chance of true (0..1).
   */
  chance(probability: number): boolean {
    return this.next() < probability;
  }

  /**
   * Pick a random element from an array.
   */
  pick<T>(array: readonly T[]): T {
    if (array.length === 0) {
      throw new Error('Cannot pick from empty array');
    }
    const idx = this.nextInt(0, array.length - 1);
    return array[idx];
  }

  /**
   * Shuffle an array in place (Fisher-Yates).
   */
  shuffle<T>(array: T[]): T[] {
    for (let i = array.length - 1; i > 0; i--) {
      const j = this.nextInt(0, i);
      [array[i], array[j]] = [array[j], array[i]];
    }
    return array;
  }
}
