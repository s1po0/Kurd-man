/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { MAP_CONFIG, TERRAIN_COSTS, TIME_CONFIG } from '../core/constants';
import { SeededRNG } from '../core/rng';
import {
  CampaignTime,
  HeroData,
  MapData,
  PartyData,
  PartyTroopStack,
  SettlementData,
  TerrainType,
} from '../core/types';
import { ITEMS } from '../data/items';
import { TROOPS } from '../data/troops';
import { findPath } from './pathfinding';

export interface CampaignSimulationState {
  map: MapData;
  hero: HeroData;
  playerParty: PartyData;
  aiParties: PartyData[];
  time: CampaignTime;
  exploredTiles: boolean[][];
  activeEncounterParty?: PartyData;
  activeSettlement?: SettlementData;
  logMessages: { text: string; time: string; type: 'info' | 'warn' | 'success' | 'combat' }[];
}

/**
 * Calculate total party size (excluding wounded or including active).
 */
export function getPartySize(party: PartyData): number {
  return party.troops.reduce((sum, t) => sum + t.count, 0);
}

/**
 * Calculate total cargo weight in party inventory.
 */
export function getPartyCargoWeight(hero: HeroData): number {
  return hero.inventory.reduce((sum, item) => {
    const def = ITEMS[item.id];
    return sum + (def ? def.weight * item.count : 0);
  }, 0);
}

/**
 * Calculate max party cargo capacity based on troops and horses.
 */
export function getPartyMaxWeight(hero: HeroData, party: PartyData): number {
  const troopCount = getPartySize(party);
  let horseCount = 0;
  for (const item of hero.inventory) {
    const def = ITEMS[item.id];
    if (def && def.type === 'horse') {
      horseCount += item.count;
    }
  }
  return 80 + troopCount * 25 + horseCount * 100 + hero.attributes.vigor * 15;
}

/**
 * Calculate max party troop capacity based on leadership and renown.
 */
export function getPartyMaxTroops(hero: HeroData): number {
  return 20 + Math.floor(hero.skills.leadership / 4) + Math.floor(hero.renown / 20) + hero.attributes.social * 3;
}

/**
 * Calculate realistic movement speed of a party.
 */
export function calculatePartySpeed(
  party: PartyData,
  terrain: TerrainType,
  isRoad: boolean,
  isNight: boolean,
  cargoWeight: number = 0,
  maxWeight: number = 300
): number {
  let baseSpeed = 4.6;

  // Party size drag
  const size = getPartySize(party);
  const sizePenalty = Math.min(1.4, (size / 100) * 1.0);
  baseSpeed -= sizePenalty;

  // Mounted troop ratio bonus
  let cavalryCount = 0;
  for (const stack of party.troops) {
    const def = TROOPS[stack.troopId];
    if (def && def.role === 'cavalry') {
      cavalryCount += stack.count;
    }
  }
  const cavalryRatio = size > 0 ? cavalryCount / size : 0;
  baseSpeed += cavalryRatio * 1.2;

  // Overburden penalty
  if (cargoWeight > maxWeight) {
    const overloadRatio = (cargoWeight - maxWeight) / maxWeight;
    baseSpeed -= Math.min(2.0, overloadRatio * 1.5);
  }

  // Morale effect
  if (party.morale < 40) {
    baseSpeed -= 0.4;
  } else if (party.morale > 75) {
    baseSpeed += 0.3;
  }

  // Terrain multiplier
  let terrainFactor = 1.0;
  if (isRoad) {
    terrainFactor = 1.2;
  } else {
    const cost = TERRAIN_COSTS[terrain] || 1.0;
    terrainFactor = 1.0 / Math.max(0.7, cost);
  }

  let finalSpeed = baseSpeed * terrainFactor;

  // Night travel penalty
  if (isNight) {
    finalSpeed *= 0.85;
  }

  return Math.max(1.8, Math.min(7.5, finalSpeed));
}

/**
 * Create initial autonomous AI parties for the world.
 */
export function createInitialAIParties(map: MapData, rng: SeededRNG): PartyData[] {
  const parties: PartyData[] = [];
  const towns = map.settlements.filter((s) => s.type === 'town');

  // 1. Lord Armies (1 per town)
  towns.forEach((town, idx) => {
    const factionPrefix = town.factionId === 'vaeloria' ? 'vael' : town.factionId === 'ironmark' ? 'iron' : town.factionId === 'dunmere' ? 'dun' : town.factionId === 'sunreach' ? 'sun' : 'frost';
    parties.push({
      id: `lord_${town.id}`,
      name: `Lord of ${town.name}`,
      partyType: 'lord',
      factionId: town.factionId,
      leaderName: `Lord ${['Gareth', 'Torvald', 'Malik', 'Bran', 'Cedric', 'Kael'][idx % 6]}`,
      x: town.x + rng.nextInt(-2, 2),
      y: town.y + rng.nextInt(-2, 2),
      path: [],
      troops: [
        { troopId: `${factionPrefix}_recruit`, count: 20, woundedCount: 0, xp: 0 },
        { troopId: `${factionPrefix}_footman`, count: 15, woundedCount: 0, xp: 0 },
        { troopId: `${factionPrefix}_sergeant`, count: 5, woundedCount: 0, xp: 0 },
      ],
      prisoners: [],
      gold: 1200,
      morale: 80,
      foodDays: 30,
      speed: 4.0,
      behavior: 'patrol',
      destinationSettlementId: town.id,
      lastDecisionTime: 0,
    });
  });

  // 2. Caravans (5 roaming merchant caravans between towns)
  for (let i = 0; i < 5; i++) {
    const origin = towns[i % towns.length];
    const destination = towns[(i + 2) % towns.length];
    parties.push({
      id: `caravan_${i}`,
      name: `${origin.name} Merchant Caravan`,
      partyType: 'caravan',
      factionId: 'neutral',
      leaderName: `Merchant Master ${['Basir', 'Helmut', 'Godfrey', 'Niall', 'Koran'][i]}`,
      x: origin.x + 3 + (i % 2) * 2,
      y: origin.y + 3 + ((i + 1) % 2) * 2,
      path: [],
      troops: [
        { troopId: 'merc_crossbow', count: 8, woundedCount: 0, xp: 0 },
        { troopId: 'merc_cavalry', count: 6, woundedCount: 0, xp: 0 },
      ],
      prisoners: [],
      gold: 800,
      morale: 65,
      foodDays: 25,
      speed: 4.4,
      behavior: 'travel',
      destinationSettlementId: destination.id,
      lastDecisionTime: 0,
    });
  }

  // 3. Bandits / Outlaw Raider Parties (7 roaming in hills and forests)
  const banditLocations = [
    { x: 30, y: 20 },
    { x: 60, y: 38 },
    { x: 22, y: 15 },
    { x: 74, y: 18 },
    { x: 40, y: 48 },
    { x: 55, y: 12 },
    { x: 68, y: 42 },
  ];

  banditLocations.forEach((loc, idx) => {
    parties.push({
      id: `bandit_${idx}`,
      name: `Forest Outlaws`,
      partyType: 'bandit',
      factionId: 'bandit',
      leaderName: `Brigand Chief ${['Red-Hand', 'Scar-Face', 'Black-Eye', 'Wolf-Bane', 'Gallow-Bird', 'Rattle-Bones', 'Grim'][idx]}`,
      x: loc.x,
      y: loc.y,
      path: [],
      troops: [
        { troopId: 'bandit_looter', count: 12 + idx * 2, woundedCount: 0, xp: 0 },
        { troopId: 'bandit_raider', count: 4 + idx, woundedCount: 0, xp: 0 },
      ],
      prisoners: [],
      gold: 90 + idx * 25,
      morale: 60,
      foodDays: 14,
      speed: 4.8,
      behavior: 'patrol',
      lastDecisionTime: 0,
    });
  });

  return parties;
}

/**
 * Advance campaign simulation by dt (in delta seconds).
 */
export function tickCampaignSimulation(
  state: CampaignSimulationState,
  realDt: number
): void {
  const { time, playerParty, aiParties, map, hero } = state;

  if (time.timeSpeed === 0) {
    return;
  }

  const speedMultiplier = time.timeSpeed;
  const gameMinutesPerRealSecond = (24 * 60) / (TIME_CONFIG.realSecondsPerGameDay / Math.max(1, speedMultiplier));
  const deltaGameMinutes = Math.max(0, realDt * gameMinutesPerRealSecond);

  // Advance clock
  time.minute = Math.max(0, time.minute + deltaGameMinutes);
  while (time.minute >= 60) {
    time.minute -= 60;
    time.hour += 1;

    // Check hourly updates
    if (time.hour >= 24) {
      time.hour = 0;
      time.day += 1;
      onDayPassed(state);
    }
  }

  const isNight = time.hour < 5 || time.hour > 20;

  // Update Fog of War around player
  updatePlayerVision(state);

  // Update Player Movement along Path
  if (playerParty.path.length > 0) {
    const currentTileX = Math.floor(playerParty.x);
    const currentTileY = Math.floor(playerParty.y);
    const currentTerrain = map.tiles[currentTileY]?.[currentTileX] || 'plains';
    const isRoad = map.roads[currentTileY]?.[currentTileX] || false;
    const cargoWeight = getPartyCargoWeight(hero);
    const maxWeight = getPartyMaxWeight(hero, playerParty);

    const speed = calculatePartySpeed(playerParty, currentTerrain, isRoad, isNight, cargoWeight, maxWeight);
    playerParty.speed = speed;

    movePartyAlongPath(playerParty, realDt * speedMultiplier, speed);

    // Check if reached destination or entered settlement
    checkPlayerSettlementArrival(state);
  }

  // Update AI Parties
  for (const party of aiParties) {
    updateAIParty(party, state, realDt * speedMultiplier, isNight);
  }

  // Proximity Encounter Detection between Player and nearby parties
  checkEncounters(state);
}

/**
 * Move party smoothly towards next waypoint in path.
 */
function movePartyAlongPath(party: PartyData, dt: number, speed: number): void {
  if (party.path.length === 0) return;

  const target = party.path[0];
  const dx = target.x - party.x;
  const dy = target.y - party.y;
  const dist = Math.sqrt(dx * dx + dy * dy);

  const step = speed * 0.45 * dt;

  if (dist <= step) {
    party.x = target.x;
    party.y = target.y;
    party.path.shift();
  } else {
    party.x += (dx / dist) * step;
    party.y += (dy / dist) * step;
  }
}

/**
 * Update utility AI for an autonomous party.
 */
function updateAIParty(
  party: PartyData,
  state: CampaignSimulationState,
  dt: number,
  isNight: boolean
): void {
  const { map, playerParty, aiParties } = state;
  const rng = new SeededRNG(Math.floor(party.x * 100 + party.y));

  // Party speed calculation
  const curX = Math.floor(party.x);
  const curY = Math.floor(party.y);
  const terrain = map.tiles[curY]?.[curX] || 'plains';
  const isRoad = map.roads[curY]?.[curX] || false;
  party.speed = calculatePartySpeed(party, terrain, isRoad, isNight);

  // Decision timer: every 3 to 6 seconds, re-evaluate behavior
  party.lastDecisionTime += dt;
  if (party.lastDecisionTime > 4.0 || party.path.length === 0) {
    party.lastDecisionTime = 0;

    if (party.partyType === 'caravan') {
      // Caravans travel between towns
      const towns = map.settlements.filter((s) => s.type === 'town');
      if (!party.destinationSettlementId || Math.random() < 0.2) {
        const dest = towns[Math.floor(Math.random() * towns.length)];
        party.destinationSettlementId = dest.id;
        party.path = findPath(party.x, party.y, dest.x, dest.y, map);
      } else {
        const dest = towns.find((t) => t.id === party.destinationSettlementId) || towns[0];
        const distToDest = Math.hypot(party.x - dest.x, party.y - dest.y);
        if (distToDest < 2.0) {
          // Reached town, pick next town!
          const nextTown = towns[(towns.indexOf(dest) + 1) % towns.length];
          party.destinationSettlementId = nextTown.id;
          party.path = findPath(party.x, party.y, nextTown.x, nextTown.y, map);
        } else if (party.path.length === 0) {
          party.path = findPath(party.x, party.y, dest.x, dest.y, map);
        }
      }
    } else if (party.partyType === 'bandit') {
      // Bandits hunt caravans or weak player, flee from lords
      const distToPlayer = Math.hypot(party.x - playerParty.x, party.y - playerParty.y);
      const playerSize = getPartySize(playerParty);
      const banditSize = getPartySize(party);

      if (distToPlayer < 7.0 && banditSize > playerSize * 1.2) {
        // Pursue player!
        party.behavior = 'pursue';
        party.path = findPath(party.x, party.y, playerParty.x, playerParty.y, map);
      } else if (distToPlayer < 6.0 && banditSize < playerSize) {
        // Flee from player!
        party.behavior = 'flee';
        const fleeX = Math.max(2, Math.min(map.width - 3, party.x + (party.x - playerParty.x) * 2));
        const fleeY = Math.max(2, Math.min(map.height - 3, party.y + (party.y - playerParty.y) * 2));
        party.path = findPath(party.x, party.y, fleeX, fleeY, map);
      } else {
        // Patrol / wander randomly in wilderness
        party.behavior = 'patrol';
        const wanderX = Math.max(2, Math.min(map.width - 3, party.x + rng.nextInt(-6, 6)));
        const wanderY = Math.max(2, Math.min(map.height - 3, party.y + rng.nextInt(-6, 6)));
        party.path = findPath(party.x, party.y, wanderX, wanderY, map);
      }
    } else if (party.partyType === 'lord') {
      // Lords patrol territory and hunt bandits
      const nearbyBandit = aiParties.find(
        (p) => p.partyType === 'bandit' && Math.hypot(party.x - p.x, party.y - p.y) < 10
      );
      if (nearbyBandit) {
        party.behavior = 'pursue';
        party.path = findPath(party.x, party.y, nearbyBandit.x, nearbyBandit.y, map);
      } else {
        // Patrol around realm
        party.behavior = 'patrol';
        const wanderX = Math.max(2, Math.min(map.width - 3, party.x + rng.nextInt(-8, 8)));
        const wanderY = Math.max(2, Math.min(map.height - 3, party.y + rng.nextInt(-8, 8)));
        party.path = findPath(party.x, party.y, wanderX, wanderY, map);
      }
    }
  }

  // Move AI party along path
  movePartyAlongPath(party, dt, party.speed);
}

/**
 * Check if player has intercepted or been intercepted by another party.
 */
function checkEncounters(state: CampaignSimulationState): void {
  const { playerParty, aiParties, time } = state;
  if (state.activeEncounterParty || state.activeSettlement) return;

  for (const party of aiParties) {
    const dist = Math.hypot(playerParty.x - party.x, playerParty.y - party.y);
    if (dist <= 1.25) {
      // Intercepted! Pause time and open encounter modal
      time.timeSpeed = 0;
      playerParty.path = [];
      state.activeEncounterParty = party;
      state.logMessages.unshift({
        text: `Intercepted ${party.name} upon the road!`,
        time: `Day ${time.day}, ${time.hour}:00`,
        type: party.partyType === 'bandit' ? 'combat' : 'info',
      });
      break;
    }
  }
}

/**
 * Check if player has stepped into a settlement.
 */
function checkPlayerSettlementArrival(state: CampaignSimulationState): void {
  const { playerParty, map, time } = state;
  if (state.activeSettlement || state.activeEncounterParty) return;

  for (const s of map.settlements) {
    const dist = Math.hypot(playerParty.x - s.x, playerParty.y - s.y);
    if (dist <= 0.8) {
      time.timeSpeed = 0;
      playerParty.path = [];
      state.activeSettlement = s;
      state.logMessages.unshift({
        text: `Arrived at the gates of ${s.name}`,
        time: `Day ${time.day}, ${time.hour}:00`,
        type: 'info',
      });
      break;
    }
  }
}

/**
 * Update revealed fog of war tiles around the player.
 */
function updatePlayerVision(state: CampaignSimulationState): void {
  const { playerParty, exploredTiles, map } = state;
  const radius = MAP_CONFIG.visionRadius;
  const px = Math.floor(playerParty.x);
  const py = Math.floor(playerParty.y);

  for (let dy = -radius; dy <= radius; dy++) {
    for (let dx = -radius; dx <= radius; dx++) {
      if (dx * dx + dy * dy <= radius * radius) {
        const tx = px + dx;
        const ty = py + dy;
        if (tx >= 0 && tx < map.width && ty >= 0 && ty < map.height) {
          exploredTiles[ty][tx] = true;
        }
      }
    }
  }
}

/**
 * Daily midnight tick: wages, food consumption, healing wounded, volunteer refresh.
 */
function onDayPassed(state: CampaignSimulationState): void {
  const { playerParty, hero, map } = state;

  // 1. Calculate and deduct daily wages
  let totalWages = 0;
  for (const stack of playerParty.troops) {
    const def = TROOPS[stack.troopId];
    if (def) {
      totalWages += def.wage * stack.count;
    }
  }
  // Stewardship skill reduces wages (up to 30%)
  const wageDiscount = Math.min(0.3, (hero.skills.steward / 100) * 0.3);
  totalWages = Math.max(1, Math.round(totalWages * (1.0 - wageDiscount)));

  if (hero.gold >= totalWages) {
    hero.gold -= totalWages;
    state.logMessages.unshift({
      text: `Paid ${totalWages} gold dinars in warband wages.`,
      time: `Day ${state.time.day}`,
      type: 'info',
    });
  } else {
    // Insufficient gold: severe morale penalty!
    hero.gold = 0;
    playerParty.morale = Math.max(5, playerParty.morale - 25);
    state.logMessages.unshift({
      text: `Warning: Unable to pay warband wages! Morale has plummeted!`,
      time: `Day ${state.time.day}`,
      type: 'warn',
    });
  }

  // 2. Food consumption
  const partySize = getPartySize(playerParty);
  const foodNeeded = Math.ceil(partySize / 10);
  let foodConsumed = 0;

  for (const item of hero.inventory) {
    const def = ITEMS[item.id];
    if (def && def.type === 'food' && item.count > 0) {
      const take = Math.min(item.count, foodNeeded - foodConsumed);
      item.count -= take;
      foodConsumed += take;
      if (foodConsumed >= foodNeeded) break;
    }
  }

  // Clean empty inventory stacks
  hero.inventory = hero.inventory.filter((i) => i.count > 0);

  if (foodConsumed < foodNeeded) {
    // Starvation!
    playerParty.morale = Math.max(0, playerParty.morale - 30);
    state.logMessages.unshift({
      text: `Starvation in the ranks! The host is out of food provisions!`,
      time: `Day ${state.time.day}`,
      type: 'warn',
    });
  } else {
    // Boost or maintain morale
    playerParty.morale = Math.min(100, playerParty.morale + 2);
  }

  // 3. Heal wounded troops (Medicine skill boosts recovery)
  const recoveryChance = 0.3 + (hero.skills.medicine / 100) * 0.4;
  for (const stack of playerParty.troops) {
    if (stack.woundedCount > 0) {
      const healed = Math.max(1, Math.floor(stack.woundedCount * recoveryChance));
      stack.woundedCount = Math.max(0, stack.woundedCount - healed);
    }
  }

  // 4. Refresh volunteers in settlements every 3 days
  if (state.time.day % 3 === 0) {
    for (const s of map.settlements) {
      const prefix = s.factionId === 'vaeloria' ? 'vael' : s.factionId === 'ironmark' ? 'iron' : s.factionId === 'dunmere' ? 'dun' : s.factionId === 'sunreach' ? 'sun' : 'frost';
      const recruitCount = s.type === 'village' ? 8 : 14;
      s.recruitsAvailable = [
        {
          troopId: `${prefix}_recruit`,
          count: recruitCount,
          woundedCount: 0,
          xp: 0,
        },
      ];
    }
  }
}
