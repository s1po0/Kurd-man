/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React from 'react';
import { HeroData, PartyData, PartyTroopStack } from '../core/types';
import { STRINGS } from '../data/strings';
import { TROOPS } from '../data/troops';
import { sound } from '../audio/audioEngine';
import { getPartyMaxTroops, getPartySize } from '../campaign/simulation';

interface PartyScreenProps {
  hero: HeroData;
  playerParty: PartyData;
  onClose: () => void;
  onUpgradeTroop: (troopId: string, upgradeToId: string, cost: number) => void;
  onDisbandTroop: (troopId: string) => void;
  onRansomPrisoners: () => void;
  onRecruitPrisoner: (troopId: string) => void;
}

export const PartyScreen: React.FC<PartyScreenProps> = ({
  hero,
  playerParty,
  onClose,
  onUpgradeTroop,
  onDisbandTroop,
  onRansomPrisoners,
  onRecruitPrisoner,
}) => {
  const partySize = getPartySize(playerParty);
  const maxTroops = getPartyMaxTroops(hero);

  // Daily wage calculation
  let totalWages = 0;
  for (const stack of playerParty.troops) {
    const def = TROOPS[stack.troopId];
    if (def) totalWages += def.wage * stack.count;
  }

  // Total ransom value of prisoners
  let totalRansomGold = 0;
  for (const stack of playerParty.prisoners) {
    const def = TROOPS[stack.troopId];
    if (def) totalRansomGold += def.tier * 20 * stack.count;
  }

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-stone-950 text-stone-100 p-3 md:p-6 select-none overflow-y-auto">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-800 pb-3 mb-4">
        <div>
          <h2 className="text-xl md:text-2xl font-bold font-display text-amber-200">
            {STRINGS.party.title}
          </h2>
          <div className="flex flex-wrap items-center gap-2 md:gap-4 text-xs text-stone-300 mt-1">
            <span className="bg-stone-900 border border-stone-800 px-2.5 py-1 rounded">
              Limit: <strong className="text-amber-200 font-mono">{partySize}/{maxTroops}</strong>
            </span>
            <span className="bg-stone-900 border border-stone-800 px-2.5 py-1 rounded">
              Wages: <strong className="text-amber-300 font-mono">{totalWages} Gold / day</strong>
            </span>
            <span className="bg-stone-900 border border-stone-800 px-2.5 py-1 rounded">
              Morale: <strong className="text-emerald-400 font-mono">{playerParty.morale}%</strong>
            </span>
          </div>
        </div>

        <button
          onClick={() => {
            sound.playClick();
            onClose();
          }}
          className="py-2 px-4 rounded bg-stone-800 hover:bg-stone-700 text-stone-200 border border-stone-700 text-xs font-bold min-h-[44px] cursor-pointer"
        >
          Return to Realm
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 flex-1">
        {/* Main Warband Troops Roster */}
        <div className="lg:col-span-2 space-y-3">
          <span className="text-xs font-bold text-amber-400 uppercase tracking-wider">
            Troop Roster & Veteran Upgrades
          </span>

          <div className="space-y-2.5 max-h-[70vh] overflow-y-auto pr-1">
            {/* Hero Card */}
            <div className="p-3.5 rounded-lg bg-stone-900 border border-amber-600/40 flex items-center justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-amber-400 text-base">👑</span>
                  <span className="font-bold text-sm text-amber-100">{hero.name}</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-950 border border-amber-600/50 text-amber-300 uppercase font-semibold">
                    Commander
                  </span>
                </div>
                <p className="text-xs text-stone-300 mt-1">
                  Health: {hero.hp}/{hero.maxHp} · Culture: {hero.culture.toUpperCase()}
                </p>
              </div>
              <div className="text-right text-xs font-mono text-amber-300">
                <span>Renown: {hero.renown}</span>
              </div>
            </div>

            {/* Troop Stacks */}
            {playerParty.troops.map((stack: PartyTroopStack) => {
              const def = TROOPS[stack.troopId];
              if (!def) return null;

              const xpRequired = def.xpToUpgrade * stack.count;
              const isEligible = def.upgradeTo && def.upgradeTo.length > 0 && stack.xp >= def.xpToUpgrade;

              return (
                <div
                  key={stack.troopId}
                  className="p-3.5 rounded-lg bg-stone-900/80 border border-stone-800 flex flex-col gap-2.5"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-sm text-stone-100">{def.name}</span>
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-stone-950 border border-stone-800 text-stone-400 uppercase font-medium">
                          Tier {def.tier} · {def.role}
                        </span>
                      </div>
                      <p className="text-xs text-stone-400 mt-0.5">
                        {def.equipmentDesc}
                      </p>
                    </div>

                    <div className="text-right">
                      <span className="font-bold text-sm text-stone-100 font-mono">
                        {stack.count}
                        {stack.woundedCount > 0 && (
                          <span className="text-red-400 text-xs font-normal">
                            {' '}
                            ({stack.woundedCount} wounded)
                          </span>
                        )}
                      </span>
                      <span className="block text-[10px] text-stone-400">
                        Wage: {def.wage * stack.count} G/day
                      </span>
                    </div>
                  </div>

                  {/* XP Progress Bar if upgradable */}
                  {def.upgradeTo && def.upgradeTo.length > 0 && (
                    <div className="space-y-1">
                      <div className="flex justify-between text-[10px] text-stone-400">
                        <span>Veteran Experience</span>
                        <span className="font-mono">
                          {stack.xp} / {def.xpToUpgrade} XP
                        </span>
                      </div>
                      <div className="w-full bg-stone-950 h-1.5 rounded-full overflow-hidden border border-stone-800">
                        <div
                          className="bg-amber-500 h-full rounded-full transition-all"
                          style={{
                            width: `${Math.min(100, (stack.xp / (def.xpToUpgrade || 1)) * 100)}%`,
                          }}
                        />
                      </div>
                    </div>
                  )}

                  {/* Promotion Actions */}
                  {isEligible && def.upgradeTo && (
                    <div className="flex gap-2 pt-1 border-t border-stone-800/60">
                      {def.upgradeTo.map((upgradeId) => {
                        const targetDef = TROOPS[upgradeId];
                        if (!targetDef) return null;
                        const upgradeCost = targetDef.tier * 20;
                        const canAfford = hero.gold >= upgradeCost;

                        return (
                          <button
                            key={upgradeId}
                            onClick={() => {
                              onUpgradeTroop(stack.troopId, upgradeId, upgradeCost);
                            }}
                            disabled={!canAfford}
                            className="py-1.5 px-3 rounded bg-gradient-to-r from-amber-700 to-amber-600 hover:from-amber-600 hover:to-amber-500 disabled:opacity-30 text-amber-100 font-bold text-xs border border-amber-500/50 shadow min-h-[36px] cursor-pointer flex items-center gap-1.5"
                          >
                            <span>⚔️</span>
                            <span>
                              Promote to {targetDef.name} ({upgradeCost} Gold)
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* Captives / Prisoners Section */}
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold text-amber-400 uppercase tracking-wider">
              {STRINGS.party.prisonersTitle}
            </span>
            {totalRansomGold > 0 && (
              <button
                onClick={() => {
                  onRansomPrisoners();
                }}
                className="py-1 px-3 rounded bg-emerald-800 hover:bg-emerald-700 text-emerald-100 text-xs font-bold min-h-[36px] cursor-pointer"
              >
                {STRINGS.party.ransomAll.replace('{gold}', String(totalRansomGold))}
              </button>
            )}
          </div>

          <div className="bg-stone-900/80 border border-stone-800 rounded-lg p-3 space-y-2 max-h-56 overflow-y-auto">
            {playerParty.prisoners.length === 0 ? (
              <p className="text-xs text-stone-500 italic p-3 text-center">
                No shackled captives currently held in luggage wagons.
              </p>
            ) : (
              playerParty.prisoners.map((stack) => {
                const def = TROOPS[stack.troopId];
                if (!def) return null;
                const ransomValue = def.tier * 20 * stack.count;

                return (
                  <div
                    key={stack.troopId}
                    className="p-2.5 rounded bg-stone-950/60 border border-stone-800 flex items-center justify-between text-xs"
                  >
                    <div>
                      <span className="font-bold text-stone-200">
                        {def.name} (x{stack.count})
                      </span>
                      <span className="block text-[10px] text-stone-400">
                        Ransom Value: {ransomValue} Gold
                      </span>
                    </div>

                    <button
                      onClick={() => {
                        onRecruitPrisoner(stack.troopId);
                      }}
                      className="py-1 px-2.5 rounded bg-stone-800 hover:bg-stone-700 border border-stone-700 text-stone-200 font-semibold text-[11px] min-h-[32px] cursor-pointer"
                    >
                      Pardon & Recruit
                    </button>
                  </div>
                );
              })
            )}
          </div>

          {/* Warband Logistics & Readiness */}
          <div className="bg-stone-900/80 border border-stone-800 rounded-lg p-3.5 space-y-2 text-xs">
            <span className="font-bold text-amber-400 uppercase tracking-wider block">
              Warband Logistics & Readiness
            </span>

            <div className="space-y-1.5 text-stone-300">
              <div className="flex justify-between">
                <span className="text-stone-400">March Speed:</span>
                <span className="font-mono font-bold text-amber-200">{playerParty.speed.toFixed(1)} km/h</span>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-400">Food Rations Remaining:</span>
                <span className="font-mono text-emerald-400 font-bold">{playerParty.foodDays} Days</span>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-400">Tactical Readiness:</span>
                <span className="text-stone-200 font-semibold">High Combat Morale</span>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-400">Sworn Culture:</span>
                <span className="text-amber-300 font-semibold uppercase">{hero.culture}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
