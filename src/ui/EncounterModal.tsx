/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { HeroData, PartyData } from '../core/types';
import { FACTIONS } from '../data/factions';
import { STRINGS } from '../data/strings';
import { TROOPS } from '../data/troops';
import { sound } from '../audio/audioEngine';
import { getPartySize } from '../campaign/simulation';

interface EncounterModalProps {
  playerParty: PartyData;
  enemyParty: PartyData;
  hero: HeroData;
  onAttack: () => void;
  onTalk: () => void;
  onBribe: (goldCost: number) => void;
  onFlee: () => void;
}

export const EncounterModal: React.FC<EncounterModalProps> = ({
  playerParty,
  enemyParty,
  hero,
  onAttack,
  onTalk,
  onBribe,
  onFlee,
}) => {
  const [talkOpen, setTalkOpen] = useState<boolean>(false);

  const playerSize = getPartySize(playerParty);
  const enemySize = getPartySize(enemyParty);
  const totalTroops = playerSize + enemySize;
  const playerRatio = totalTroops > 0 ? (playerSize / totalTroops) * 100 : 50;

  const bribeCost = Math.max(25, Math.floor(enemySize * 6));
  const canAffordBribe = hero.gold >= bribeCost;

  const faction = FACTIONS[enemyParty.factionId as keyof typeof FACTIONS] || {
    name: enemyParty.partyType === 'bandit' ? 'Outlaws of Caladria' : 'Independent Guild',
    primaryColor: enemyParty.partyType === 'bandit' ? '#7f1d1d' : '#475569',
    secondaryColor: enemyParty.partyType === 'bandit' ? '#ef4444' : '#cbd5e1',
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-2 sm:p-4 select-none overflow-y-auto">
      <div className="w-full max-w-lg max-h-[96vh] overflow-y-auto bg-stone-950 border border-stone-800 rounded-xl p-3.5 sm:p-5 shadow-2xl text-stone-100 flex flex-col space-y-3 sm:space-y-4 animate-scale-up">
        {/* Header Interception */}
        <div className="flex items-center gap-3 border-b border-stone-800 pb-3">
          <div
            className="w-12 h-14 rounded border flex items-center justify-center text-xl font-bold"
            style={{
              backgroundColor: faction.primaryColor,
              borderColor: faction.secondaryColor,
              color: faction.secondaryColor,
            }}
          >
            {enemyParty.partyType === 'bandit'
              ? '💀'
              : enemyParty.partyType === 'caravan'
              ? '🐪'
              : '⚔️'}
          </div>

          <div>
            <span className="text-[11px] text-amber-400 uppercase font-bold tracking-wider">
              {STRINGS.encounter.spottedParty}
            </span>
            <h3 className="text-xl font-bold font-display text-amber-100">
              {enemyParty.name}
            </h3>
            <p className="text-xs text-stone-400">
              Commanded by <strong className="text-stone-200">{enemyParty.leaderName}</strong> ·{' '}
              {faction.name}
            </p>
          </div>
        </div>

        {/* Comparative Troop Balance Bar */}
        <div className="space-y-1.5 bg-stone-900/80 p-3 rounded-lg border border-stone-800">
          <div className="flex justify-between text-xs font-semibold">
            <span className="text-amber-300">
              Your Host: <strong className="font-mono">{playerSize}</strong>
            </span>
            <span className="text-red-400">
              Hostile Host: <strong className="font-mono">{enemySize}</strong>
            </span>
          </div>

          <div className="w-full bg-red-950 h-2.5 rounded-full overflow-hidden border border-stone-800 flex">
            <div
              className="bg-amber-500 h-full transition-all"
              style={{ width: `${playerRatio}%` }}
            />
            <div
              className="bg-red-600 h-full transition-all"
              style={{ width: `${100 - playerRatio}%` }}
            />
          </div>

          {/* Enemy troops breakdown preview */}
          <div className="flex flex-wrap gap-1.5 pt-1 text-[10px] text-stone-400">
            <span>Roster:</span>
            {enemyParty.troops.map((t) => {
              const def = TROOPS[t.troopId];
              return def ? (
                <span key={t.troopId} className="px-1.5 py-0.5 rounded bg-stone-950 border border-stone-800">
                  {def.name} x{t.count}
                </span>
              ) : null;
            })}
          </div>
        </div>

        {/* Parley Conversation Dialog if opened */}
        {talkOpen ? (
          <div className="p-3 bg-stone-900 rounded-lg border border-stone-800 text-xs space-y-2">
            <p className="text-stone-300 italic font-serif">
              &ldquo;
              {enemyParty.partyType === 'bandit'
                ? "Your purse or your blood, wanderer! Hand over your gold dinars if you value your life!"
                : enemyParty.partyType === 'caravan'
                ? "Greetings traveler! We are on our way between city markets. May our roads be peaceful."
                : "Hold! State your business and allegiance in these lands, commander."}
              &rdquo;
            </p>
            <div className="flex justify-end pt-2">
              <button
                onClick={() => setTalkOpen(false)}
                className="py-1.5 px-3 rounded bg-stone-800 hover:bg-stone-700 text-stone-300 text-xs font-semibold"
              >
                End Conversation
              </button>
            </div>
          </div>
        ) : null}

        {/* Action Choice Buttons */}
        <div className="space-y-2 pt-1">
          {/* Attack */}
          <button
            onClick={() => {
              sound.playClash();
              onAttack();
            }}
            className="w-full py-3.5 px-4 rounded-lg bg-gradient-to-r from-red-800 to-amber-700 hover:from-red-700 hover:to-amber-600 text-amber-100 font-bold text-xs tracking-wider border border-amber-500/50 shadow-md shadow-red-950/40 min-h-[48px] cursor-pointer flex items-center justify-center gap-2"
          >
            <span>⚔️</span>
            <span>{STRINGS.encounter.actionAttack}</span>
          </button>

          {/* Talk */}
          <button
            onClick={() => {
              sound.playClick();
              setTalkOpen(!talkOpen);
            }}
            className="w-full py-2.5 px-4 rounded-lg bg-stone-900 hover:bg-stone-800 border border-stone-700 text-stone-200 font-semibold text-xs min-h-[44px] cursor-pointer"
          >
            {STRINGS.encounter.actionTalk}
          </button>

          <div className="grid grid-cols-2 gap-2">
            {/* Bribe / Toll */}
            <button
              onClick={() => {
                if (canAffordBribe) {
                  sound.playCoin();
                  onBribe(bribeCost);
                }
              }}
              disabled={!canAffordBribe}
              className="py-2.5 px-3 rounded-lg bg-stone-900 hover:bg-stone-800 disabled:opacity-30 border border-stone-700 text-stone-300 text-xs font-semibold min-h-[44px] cursor-pointer"
            >
              Pay Toll ({bribeCost} G)
            </button>

            {/* Flee */}
            <button
              onClick={() => {
                sound.playMarchStep();
                onFlee();
              }}
              className="py-2.5 px-3 rounded-lg bg-stone-900 hover:bg-stone-800 border border-stone-700 text-stone-300 text-xs font-semibold min-h-[44px] cursor-pointer"
            >
              Sound Retreat
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
