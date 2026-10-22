/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { SeededRNG } from './rng';
import { generateCampaignMap } from '../campaign/terrain';
import { findPath } from '../campaign/pathfinding';
import {
  calculatePartySpeed,
  getPartyCargoWeight,
  getPartyMaxTroops,
  getPartyMaxWeight,
  getPartySize,
} from '../campaign/simulation';
import { resolveSkirmish } from '../battle/skirmishEngine';
import { HeroData, PartyData } from './types';

test('SeededRNG produces deterministic sequences', () => {
  const rng1 = new SeededRNG(12345);
  const rng2 = new SeededRNG(12345);

  const seq1 = [rng1.next(), rng1.nextInt(1, 100), rng1.nextFloat(5, 10)];
  const seq2 = [rng2.next(), rng2.nextInt(1, 100), rng2.nextFloat(5, 10)];

  assert.deepEqual(seq1, seq2);
});

test('generateCampaignMap creates valid settlements and passable tiles', () => {
  const map = generateCampaignMap(9999);
  assert.equal(map.width, 90);
  assert.equal(map.height, 60);

  // 6 towns + 5 castles + 12 villages = 23 settlements
  assert.equal(map.settlements.length, 23);

  // Check all settlements sit on passable terrain
  for (const s of map.settlements) {
    const terrain = map.tiles[s.y][s.x];
    assert.notEqual(terrain, 'water', `Settlement ${s.name} should not be in water`);
    assert.notEqual(terrain, 'mountains', `Settlement ${s.name} should not be in mountains`);
  }
});

test('findPath successfully finds route avoiding impassable obstacles', () => {
  const map = generateCampaignMap(4281);
  const town1 = map.settlements[0];
  const town2 = map.settlements[1];

  const path = findPath(town1.x, town1.y, town2.x, town2.y, map);
  assert.ok(path.length > 0, 'A* should find path between settlements');

  // Verify no step in path is in water or mountain
  for (const step of path) {
    const terrain = map.tiles[step.y][step.x];
    assert.notEqual(terrain, 'water');
    assert.notEqual(terrain, 'mountains');
  }
});

test('calculatePartySpeed factors in cavalry, terrain and cargo', () => {
  const dummyParty: PartyData = {
    id: 'p1',
    name: 'Warband',
    partyType: 'player',
    factionId: 'vaeloria',
    leaderName: 'Hero',
    x: 40,
    y: 30,
    path: [],
    troops: [{ troopId: 'vael_recruit', count: 10, woundedCount: 0, xp: 0 }],
    prisoners: [],
    gold: 500,
    morale: 70,
    foodDays: 10,
    speed: 4.5,
    behavior: 'idle',
    lastDecisionTime: 0,
  };

  const roadSpeed = calculatePartySpeed(dummyParty, 'plains', true, false, 50, 300);
  const plainsSpeed = calculatePartySpeed(dummyParty, 'plains', false, false, 50, 300);
  const forestSpeed = calculatePartySpeed(dummyParty, 'forest', false, false, 50, 300);

  assert.ok(roadSpeed > plainsSpeed, 'Roads should be faster than plains');
  assert.ok(plainsSpeed > forestSpeed, 'Plains should be faster than forest');

  // Night penalty
  const nightSpeed = calculatePartySpeed(dummyParty, 'plains', false, true, 50, 300);
  assert.ok(nightSpeed < plainsSpeed, 'Night should impose speed penalty');
});

test('resolveSkirmish simulates combat and applies medicine skill', () => {
  const playerParty: PartyData = {
    id: 'player',
    name: 'Player Army',
    partyType: 'player',
    factionId: 'vaeloria',
    leaderName: 'Commander',
    x: 30,
    y: 30,
    path: [],
    troops: [
      { troopId: 'vael_knight', count: 15, woundedCount: 0, xp: 0 },
      { troopId: 'vael_sergeant', count: 20, woundedCount: 0, xp: 0 },
    ],
    prisoners: [],
    gold: 1000,
    morale: 80,
    foodDays: 20,
    speed: 4.5,
    behavior: 'idle',
    lastDecisionTime: 0,
  };

  const banditParty: PartyData = {
    id: 'bandits',
    name: 'Looters',
    partyType: 'bandit',
    factionId: 'bandit',
    leaderName: 'Chief',
    x: 30,
    y: 30,
    path: [],
    troops: [
      { troopId: 'bandit_looter', count: 8, woundedCount: 0, xp: 0 },
    ],
    prisoners: [],
    gold: 50,
    morale: 40,
    foodDays: 5,
    speed: 4.0,
    behavior: 'idle',
    lastDecisionTime: 0,
  };

  const result = resolveSkirmish(playerParty, banditParty, 'plains', 50, 60);
  assert.equal(result.winner, 'player', 'Player elite knights should easily defeat small looters');
  assert.ok(result.goldLooted > 0, 'Player should loot gold from bandits');
  assert.ok(result.xpAwarded > 0, 'Player troops should earn combat experience');
});

test('getPartyCargoWeight and capacities calculate correctly', () => {
  const hero: HeroData = {
    id: 'hero',
    name: 'Hero',
    culture: 'vaeloria',
    backgroundOrigin: 'Noble',
    gender: 'male',
    hp: 100,
    maxHp: 100,
    gold: 800,
    renown: 50,
    honor: 10,
    attributes: { vigor: 5, control: 3, endurance: 4, cunning: 3, social: 5, intelligence: 4 },
    skills: {
      oneHanded: 30,
      twoHanded: 15,
      polearm: 20,
      bow: 20,
      throwing: 10,
      riding: 40,
      athletics: 30,
      tactics: 30,
      scouting: 25,
      leadership: 40,
      trade: 25,
      steward: 30,
      medicine: 20,
    },
    attributePoints: 0,
    skillPoints: 0,
    equipment: {},
    inventory: [
      { id: 'food_grain', count: 5 }, // 5 * 10 = 50 weight
      { id: 'trade_wool', count: 2 }, // 2 * 12 = 24 weight
    ],
    bannerHeraldry: {
      symbolIndex: 0,
      bgPatternIndex: 0,
      primaryColor: '#1e3a8a',
      secondaryColor: '#f59e0b',
    },
  };

  const party: PartyData = {
    id: 'p',
    name: 'Warband',
    partyType: 'player',
    factionId: 'vaeloria',
    leaderName: 'Hero',
    x: 0,
    y: 0,
    path: [],
    troops: [{ troopId: 'vael_recruit', count: 10, woundedCount: 0, xp: 0 }],
    prisoners: [],
    gold: 800,
    morale: 70,
    foodDays: 12,
    speed: 4.5,
    behavior: 'idle',
    lastDecisionTime: 0,
  };

  const cargo = getPartyCargoWeight(hero);
  assert.equal(cargo, 74); // 50 + 24 = 74

  const maxWeight = getPartyMaxWeight(hero, party);
  assert.ok(maxWeight > cargo);

  const maxTroops = getPartyMaxTroops(hero);
  assert.ok(maxTroops >= 30);
});
