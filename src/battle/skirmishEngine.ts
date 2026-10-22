/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { PartyData, PartyTroopStack, TerrainType } from '../core/types';
import { ITEMS } from '../data/items';
import { TROOPS } from '../data/troops';

export interface SkirmishResult {
  winner: 'player' | 'enemy';
  rounds: number;
  playerInitialCount: number;
  enemyInitialCount: number;
  playerKilled: number;
  playerWounded: number;
  enemyKilled: number;
  enemyWounded: number;
  goldLooted: number;
  renownEarned: number;
  xpAwarded: number;
  prisonersCaptured: PartyTroopStack[];
  itemsLooted: string[];
}

/**
 * Pure simulation auto-resolve battle calculator.
 */
export function resolveSkirmish(
  playerParty: PartyData,
  enemyParty: PartyData,
  terrain: TerrainType,
  playerTacticsSkill: number = 20,
  playerMedicineSkill: number = 20
): SkirmishResult {
  const getPartyTotalCount = (p: PartyData) =>
    p.troops.reduce((sum, t) => sum + (t.count - t.woundedCount), 0);

  const playerInitialCount = getPartyTotalCount(playerParty);
  const enemyInitialCount = getPartyTotalCount(enemyParty);

  // Deep clone troop stacks to mutate during combat simulation
  const playerTroops = playerParty.troops.map((t) => ({ ...t }));
  const enemyTroops = enemyParty.troops.map((t) => ({ ...t }));

  let playerKilledTotal = 0;
  let playerWoundedTotal = 0;
  let enemyKilledTotal = 0;
  let enemyWoundedTotal = 0;

  // Medicine survival rate: base 35% + 0.35% per medicine skill point (up to 70%)
  const medicineSaveRate = Math.min(0.7, 0.35 + (playerMedicineSkill / 100) * 0.35);

  let round = 0;
  const maxRounds = 12;

  while (round < maxRounds) {
    round++;

    const playerAlive = playerTroops.reduce((sum, t) => sum + (t.count - t.woundedCount), 0);
    const enemyAlive = enemyTroops.reduce((sum, t) => sum + (t.count - t.woundedCount), 0);

    if (playerAlive <= 0 || enemyAlive <= 0) break;

    // Calculate offensive power of each side
    let playerPower = 0;
    for (const stack of playerTroops) {
      const active = stack.count - stack.woundedCount;
      if (active <= 0) continue;
      const def = TROOPS[stack.troopId] || TROOPS['vael_recruit'];
      let power = def.attack * (0.8 + def.tier * 0.2);

      // Terrain modifiers
      if (def.role === 'cavalry' && (terrain === 'forest' || terrain === 'hills')) {
        power *= 0.65; // Cavalry hindered in forest/hills
      } else if (def.role === 'cavalry' && terrain === 'plains') {
        power *= 1.25; // Cavalry excels on open plains
      } else if (def.role === 'archer' && terrain === 'hills') {
        power *= 1.2; // High ground archery bonus
      }

      playerPower += power * active;
    }

    let enemyPower = 0;
    for (const stack of enemyTroops) {
      const active = stack.count - stack.woundedCount;
      if (active <= 0) continue;
      const def = TROOPS[stack.troopId] || TROOPS['bandit_looter'];
      let power = def.attack * (0.8 + def.tier * 0.2);

      if (def.role === 'cavalry' && (terrain === 'forest' || terrain === 'hills')) {
        power *= 0.65;
      } else if (def.role === 'cavalry' && terrain === 'plains') {
        power *= 1.25;
      }

      enemyPower += power * active;
    }

    // Tactics modifier (each 10 tactics gives +5% effective combat advantage)
    playerPower *= 1.0 + (playerTacticsSkill / 100) * 0.5;

    // Determine casualties inflicted this round
    const casualtiesOnEnemy = Math.max(1, Math.floor((playerPower / (enemyAlive * 25 + 50)) * 2));
    const casualtiesOnPlayer = Math.max(0, Math.floor((enemyPower / (playerAlive * 30 + 50)) * 2));

    // Apply casualties to enemy
    for (let c = 0; c < casualtiesOnEnemy; c++) {
      const targetStack = enemyTroops.find((t) => t.count - t.woundedCount > 0);
      if (!targetStack) break;

      // 40% wounded, 60% killed for enemy
      if (Math.random() < 0.4) {
        targetStack.woundedCount++;
        enemyWoundedTotal++;
      } else {
        targetStack.count--;
        enemyKilledTotal++;
      }
    }

    // Apply casualties to player
    for (let c = 0; c < casualtiesOnPlayer; c++) {
      const targetStack = playerTroops.find((t) => t.count - t.woundedCount > 0);
      if (!targetStack) break;

      // Player benefits from medicineSaveRate!
      if (Math.random() < medicineSaveRate) {
        targetStack.woundedCount++;
        playerWoundedTotal++;
      } else {
        targetStack.count--;
        playerKilledTotal++;
      }
    }
  }

  const finalPlayerAlive = playerTroops.reduce((sum, t) => sum + (t.count - t.woundedCount), 0);
  const finalEnemyAlive = enemyTroops.reduce((sum, t) => sum + (t.count - t.woundedCount), 0);

  const winner: 'player' | 'enemy' = finalPlayerAlive >= finalEnemyAlive ? 'player' : 'enemy';

  // Apply results back to playerParty
  playerParty.troops = playerTroops.filter((t) => t.count > 0);

  // Spoils calculation if player won
  let goldLooted = 0;
  let renownEarned = 0;
  let xpAwarded = 0;
  const prisonersCaptured: PartyTroopStack[] = [];
  const itemsLooted: string[] = [];

  if (winner === 'player') {
    goldLooted = Math.floor(enemyParty.gold * 0.75) + enemyInitialCount * 8;
    renownEarned = Math.max(2, Math.floor(enemyInitialCount / 3));
    xpAwarded = enemyInitialCount * 45;

    // Distribute XP to surviving player troops
    if (playerParty.troops.length > 0) {
      const xpPerStack = Math.floor(xpAwarded / playerParty.troops.length);
      for (const stack of playerParty.troops) {
        stack.xp += xpPerStack;
      }
    }

    // Capture some wounded enemies as prisoners
    for (const stack of enemyTroops) {
      if (stack.woundedCount > 0) {
        const captured = Math.min(stack.woundedCount, 5);
        if (captured > 0) {
          prisonersCaptured.push({
            troopId: stack.troopId,
            count: captured,
            woundedCount: captured,
            xp: 0,
          });
        }
      }
    }

    // Loot potential items based on enemy tier
    const itemKeys = Object.keys(ITEMS);
    const lootCount = Math.min(3, Math.floor(enemyInitialCount / 4));
    for (let i = 0; i < lootCount; i++) {
      const randomItem = itemKeys[Math.floor(Math.random() * itemKeys.length)];
      itemsLooted.push(randomItem);
    }
  }

  return {
    winner,
    rounds: round,
    playerInitialCount,
    enemyInitialCount,
    playerKilled: playerKilledTotal,
    playerWounded: playerWoundedTotal,
    enemyKilled: enemyKilledTotal,
    enemyWounded: enemyWoundedTotal,
    goldLooted,
    renownEarned,
    xpAwarded,
    prisonersCaptured,
    itemsLooted,
  };
}
