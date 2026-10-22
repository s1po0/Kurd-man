/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React from 'react';
import { SkirmishResult } from '../battle/skirmishEngine';
import { ITEMS } from '../data/items';
import { STRINGS } from '../data/strings';
import { TROOPS } from '../data/troops';
import { sound } from '../audio/audioEngine';

interface BattleReportModalProps {
  result: SkirmishResult;
  onClose: () => void;
}

export const BattleReportModal: React.FC<BattleReportModalProps> = ({ result, onClose }) => {
  const isVictory = result.winner === 'player';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/90 p-2 sm:p-4 select-none overflow-y-auto">
      <div className="w-full max-w-lg max-h-[96vh] overflow-y-auto bg-stone-950 border border-stone-800 rounded-xl p-3.5 sm:p-5 shadow-2xl text-stone-100 flex flex-col space-y-3 sm:space-y-4 animate-scale-up">
        {/* Victory / Defeat Header */}
        <div
          className={`p-4 rounded-lg border text-center ${
            isVictory
              ? 'bg-amber-950/60 border-amber-500/50 text-amber-200'
              : 'bg-red-950/60 border-red-500/50 text-red-200'
          }`}
        >
          <span className="text-3xl block mb-1">{isVictory ? '🏆' : '💀'}</span>
          <h3 className="text-2xl font-bold font-display uppercase tracking-wider">
            {isVictory ? STRINGS.battle.victory : STRINGS.battle.defeat}
          </h3>
          <p className="text-xs text-stone-300 font-serif italic mt-0.5">
            Battle concluded after {result.rounds} rounds of furious combat.
          </p>
        </div>

        {/* Casualty Ledger */}
        <div className="grid grid-cols-2 gap-3 text-xs">
          {/* Friendly Casualties */}
          <div className="p-3 bg-stone-900/80 rounded-lg border border-stone-800 space-y-1.5">
            <span className="font-bold text-amber-300 block border-b border-stone-800 pb-1">
              {STRINGS.battle.playerCasualties}
            </span>
            <div className="flex justify-between">
              <span className="text-stone-400">{STRINGS.battle.killed}:</span>
              <span className="font-mono font-bold text-red-400">{result.playerKilled}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-stone-400">{STRINGS.battle.wounded}:</span>
              <span className="font-mono font-bold text-amber-300">{result.playerWounded}</span>
            </div>
            <p className="text-[10px] text-stone-400 pt-1 italic">
              Medicine skill preserved wounded warriors from death.
            </p>
          </div>

          {/* Enemy Casualties */}
          <div className="p-3 bg-stone-900/80 rounded-lg border border-stone-800 space-y-1.5">
            <span className="font-bold text-red-400 block border-b border-stone-800 pb-1">
              {STRINGS.battle.enemyCasualties}
            </span>
            <div className="flex justify-between">
              <span className="text-stone-400">{STRINGS.battle.killed}:</span>
              <span className="font-mono font-bold text-stone-200">{result.enemyKilled}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-stone-400">{STRINGS.battle.wounded}:</span>
              <span className="font-mono font-bold text-stone-200">{result.enemyWounded}</span>
            </div>
          </div>
        </div>

        {/* Spoils of War if Victorious */}
        {isVictory && (
          <div className="p-3 bg-stone-900/80 rounded-lg border border-stone-800 space-y-2 text-xs">
            <span className="font-bold text-amber-400 uppercase tracking-wider block">
              Martial Spoils & Acclaims
            </span>

            <div className="grid grid-cols-3 gap-2 text-center">
              <div className="p-2 rounded bg-stone-950/60 border border-stone-800">
                <span className="block text-[10px] text-stone-400">{STRINGS.battle.goldPlundered}</span>
                <span className="text-sm font-bold font-mono text-amber-300">
                  +{result.goldLooted} D
                </span>
              </div>

              <div className="p-2 rounded bg-stone-950/60 border border-stone-800">
                <span className="block text-[10px] text-stone-400">{STRINGS.battle.xpAwarded}</span>
                <span className="text-sm font-bold font-mono text-emerald-400">
                  +{result.xpAwarded} XP
                </span>
              </div>

              <div className="p-2 rounded bg-stone-950/60 border border-stone-800">
                <span className="block text-[10px] text-stone-400">Sovereign Renown</span>
                <span className="text-sm font-bold font-mono text-amber-200">
                  +{result.renownEarned}
                </span>
              </div>
            </div>

            {/* Prisoners Captured */}
            {result.prisonersCaptured.length > 0 && (
              <div className="pt-1 text-[11px] text-stone-300">
                <span className="text-amber-300 font-bold">Captives Shackled: </span>
                {result.prisonersCaptured.map((p, idx) => (
                  <span key={idx}>
                    {TROOPS[p.troopId]?.name || 'Captive'} (x{p.count})
                    {idx < result.prisonersCaptured.length - 1 ? ', ' : ''}
                  </span>
                ))}
              </div>
            )}

            {/* Items Looted */}
            {result.itemsLooted.length > 0 && (
              <div className="pt-1 text-[11px] text-stone-300">
                <span className="text-amber-300 font-bold">Looted Gear: </span>
                {result.itemsLooted.map((id, idx) => (
                  <span key={idx}>
                    {ITEMS[id]?.name || 'Spoil'}
                    {idx < result.itemsLooted.length - 1 ? ', ' : ''}
                  </span>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Action Button */}
        <button
          onClick={() => {
            sound.playCoin();
            onClose();
          }}
          className="w-full py-3.5 px-4 rounded-lg bg-gradient-to-r from-amber-700 to-amber-600 hover:from-amber-600 hover:to-amber-500 text-stone-100 font-bold text-xs tracking-wider border border-amber-500/50 shadow min-h-[48px] cursor-pointer"
        >
          {STRINGS.battle.proceed}
        </button>
      </div>
    </div>
  );
};
