/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React from 'react';
import { MONTH_NAMES } from '../core/constants';
import { CampaignTime, GameView, HeroData, PartyData } from '../core/types';
import { STRINGS } from '../data/strings';
import { sound } from '../audio/audioEngine';
import { getPartyMaxTroops, getPartySize } from '../campaign/simulation';

interface HUDProps {
  hero: HeroData;
  playerParty: PartyData;
  time: CampaignTime;
  activeView: GameView;
  onSetTimeSpeed: (speed: 0 | 1 | 2 | 4) => void;
  onOpenView: (view: GameView) => void;
  onCampRest: () => void;
  logMessage?: string | null;
}

export const HUD: React.FC<HUDProps> = ({
  hero,
  playerParty,
  time,
  activeView,
  onSetTimeSpeed,
  onOpenView,
  onCampRest,
  logMessage,
}) => {
  const partySize = getPartySize(playerParty);
  const maxTroops = getPartyMaxTroops(hero);

  const monthName = MONTH_NAMES[(time.month - 1) % MONTH_NAMES.length];
  const isNight = time.hour < 5 || time.hour > 20;
  const [isMuted, setIsMuted] = React.useState(sound.getMuted());

  return (
    <div className="pointer-events-none absolute inset-0 flex flex-col justify-between p-1.5 md:p-3 select-none z-30">
      {/* Top Group: Header Status Bar + Toast underneath */}
      <div className="w-full flex flex-col items-center gap-2 pointer-events-auto">
        {/* Top Header Status Bar */}
        <div className="flex flex-col sm:flex-row items-center justify-between gap-1.5 bg-stone-950 border border-stone-800 rounded-lg p-2 shadow-xl shadow-black/80 w-full max-w-3xl">
          {/* Row 1 on mobile: Date/Time + Resources */}
          <div className="flex flex-wrap items-center justify-between w-full sm:w-auto gap-2.5 text-xs">
            {/* Date & Time */}
            <div className="flex items-center gap-1.5 text-amber-400 font-bold text-xs">
              <span>{isNight ? '🌙' : '☀️'}</span>
              <span className="font-display">Day {time.day}</span>
              <span className="text-stone-500 text-[10px]">·</span>
              <span className="tabular-nums font-mono text-stone-300">
                {String(Math.max(0, Math.min(23, time.hour))).padStart(2, '0')}:
                {String(Math.max(0, Math.min(59, Math.floor(time.minute)))).padStart(2, '0')}
              </span>
              <span className="hidden md:inline text-[10px] text-stone-400 font-normal">
                ({monthName})
              </span>
            </div>

            {/* Resources */}
            <div className="flex items-center gap-2.5 md:gap-4 text-xs font-mono">
              {/* Gold */}
              <div className="flex items-center gap-1 text-amber-300" title={STRINGS.gold}>
                <span>🪙</span>
                <span className="font-bold tabular-nums">{hero.gold}</span>
              </div>

              {/* Food Days */}
              <div
                className={`flex items-center gap-1 ${
                  playerParty.foodDays <= 3 ? 'text-red-400 font-bold animate-pulse' : 'text-stone-300'
                }`}
                title="Food Provisions"
              >
                <span>🥖</span>
                <span className="tabular-nums">
                  {hero.inventory.filter((i) => i.id.startsWith('food_')).reduce((s, f) => s + f.count, 0)}
                </span>
              </div>

              {/* Morale */}
              <div className="flex items-center gap-1 text-stone-300" title={STRINGS.morale}>
                <span>⚔️</span>
                <span className="tabular-nums">{playerParty.morale}%</span>
              </div>

              {/* Party Size */}
              <div className="flex items-center gap-1 text-stone-200" title={STRINGS.troops}>
                <span>🛡️</span>
                <span className="tabular-nums">
                  {partySize}/{maxTroops}
                </span>
              </div>
            </div>
          </div>

          {/* Time Speed Controls */}
          <div className="flex items-center justify-end gap-1 bg-stone-900 rounded p-0.5 border border-stone-800 self-end sm:self-auto shrink-0">
            <button
              onClick={() => {
                sound.playClick();
                onSetTimeSpeed(0);
              }}
              className={`px-2.5 py-1 text-xs font-bold rounded min-h-[36px] min-w-[32px] transition-colors cursor-pointer ${
                time.timeSpeed === 0
                  ? 'bg-amber-600 text-stone-100 shadow'
                  : 'text-stone-400 hover:text-stone-200'
              }`}
              title="Pause"
            >
              ⏸
            </button>
            <button
              onClick={() => {
                sound.playClick();
                onSetTimeSpeed(1);
              }}
              className={`px-2.5 py-1 text-xs font-bold rounded min-h-[36px] min-w-[32px] transition-colors cursor-pointer ${
                time.timeSpeed === 1
                  ? 'bg-amber-600 text-stone-100 shadow'
                  : 'text-stone-400 hover:text-stone-200'
              }`}
              title="1x Speed"
            >
              1x
            </button>
            <button
              onClick={() => {
                sound.playClick();
                onSetTimeSpeed(2);
              }}
              className={`px-2.5 py-1 text-xs font-bold rounded min-h-[36px] min-w-[32px] transition-colors cursor-pointer ${
                time.timeSpeed === 2
                  ? 'bg-amber-600 text-stone-100 shadow'
                  : 'text-stone-400 hover:text-stone-200'
              }`}
              title="2x Speed"
            >
              2x
            </button>
            <button
              onClick={() => {
                sound.playClick();
                onSetTimeSpeed(4);
              }}
              className={`px-2.5 py-1 text-xs font-bold rounded min-h-[36px] min-w-[32px] transition-colors cursor-pointer ${
                time.timeSpeed === 4
                  ? 'bg-amber-600 text-stone-100 shadow'
                  : 'text-stone-400 hover:text-stone-200'
              }`}
              title="4x Speed"
            >
              4x
            </button>

            <div className="w-[1px] h-5 bg-stone-800 mx-0.5" />

            {/* Instant Audio Toggle */}
            <button
              onClick={() => {
                const nextMuted = sound.toggleMuted();
                setIsMuted(nextMuted);
                if (!nextMuted) sound.playClick();
              }}
              className={`px-2 py-1 text-xs font-bold rounded min-h-[36px] min-w-[32px] transition-colors cursor-pointer ${
                isMuted
                  ? 'bg-red-950/80 text-red-400 border border-red-800/60'
                  : 'text-stone-300 hover:text-amber-400 hover:bg-stone-800/60'
              }`}
              title={isMuted ? 'Unmute Sound' : 'Mute Sound'}
            >
              {isMuted ? '🔇' : '🔊'}
            </button>
          </div>
        </div>

        {/* Toast Notification Banner - positioned directly under header bar with dismiss button */}
        {logMessage && (
          <div className="bg-stone-900/95 border border-amber-500/70 text-amber-200 text-xs py-1.5 px-4 rounded-full shadow-2xl flex items-center gap-2 max-w-sm text-center animate-fade-in pointer-events-auto">
            <span>{logMessage}</span>
          </div>
        )}
      </div>

      {/* Bottom Action Navigation Dock - 360px mobile optimized */}
      <div className="flex items-center justify-center pointer-events-auto pb-1">
        <div className="flex items-center justify-center gap-1 bg-stone-950 border border-stone-800 p-1 rounded-xl shadow-2xl shadow-black w-full max-w-md mx-auto">
          <button
            onClick={() => {
              sound.playClick();
              onOpenView(activeView === 'PARTY_MENU' ? 'CAMPAIGN' : 'PARTY_MENU');
            }}
            className={`flex-1 flex flex-col sm:flex-row items-center justify-center gap-0.5 sm:gap-1 py-1.5 px-1 rounded-lg text-[11px] font-bold transition-all min-h-[44px] cursor-pointer ${
              activeView === 'PARTY_MENU'
                ? 'bg-amber-700 text-stone-100 border border-amber-400 shadow'
                : 'bg-stone-900 text-stone-300 hover:bg-stone-800 border border-stone-800'
            }`}
          >
            <span className="text-sm">🛡️</span>
            <span>Party</span>
          </button>

          <button
            onClick={() => {
              sound.playClick();
              onOpenView(activeView === 'INVENTORY_MENU' ? 'CAMPAIGN' : 'INVENTORY_MENU');
            }}
            className={`flex-1 flex flex-col sm:flex-row items-center justify-center gap-0.5 sm:gap-1 py-1.5 px-1 rounded-lg text-[11px] font-bold transition-all min-h-[44px] cursor-pointer ${
              activeView === 'INVENTORY_MENU'
                ? 'bg-amber-700 text-stone-100 border border-amber-400 shadow'
                : 'bg-stone-900 text-stone-300 hover:bg-stone-800 border border-stone-800'
            }`}
          >
            <span className="text-sm">🎒</span>
            <span>Bags</span>
          </button>

          <button
            onClick={() => {
              sound.playClick();
              onOpenView(activeView === 'CHARACTER_SHEET' ? 'CAMPAIGN' : 'CHARACTER_SHEET');
            }}
            className={`flex-1 flex flex-col sm:flex-row items-center justify-center gap-0.5 sm:gap-1 py-1.5 px-1 rounded-lg text-[11px] font-bold transition-all min-h-[44px] cursor-pointer ${
              activeView === 'CHARACTER_SHEET'
                ? 'bg-amber-700 text-stone-100 border border-amber-400 shadow'
                : 'bg-stone-900 text-stone-300 hover:bg-stone-800 border border-stone-800'
            }`}
          >
            <span className="text-sm">📜</span>
            <span>Hero</span>
          </button>

          <button
            onClick={() => {
              sound.playClick();
              onCampRest();
            }}
            className="flex-1 flex flex-col sm:flex-row items-center justify-center gap-0.5 sm:gap-1 py-1.5 px-1 rounded-lg text-[11px] font-bold bg-stone-900 text-amber-400 hover:bg-stone-800 border border-stone-800 min-h-[44px] cursor-pointer"
            title="Camp and Rest"
          >
            <span className="text-sm">⛺</span>
            <span>Rest</span>
          </button>

          <button
            onClick={() => {
              sound.playClick();
              onOpenView('SAVELOAD');
            }}
            className="flex-1 flex flex-col sm:flex-row items-center justify-center gap-0.5 sm:gap-1 py-1.5 px-1 rounded-lg text-[11px] font-bold bg-stone-900 text-stone-300 hover:bg-stone-800 border border-stone-800 min-h-[44px] cursor-pointer"
          >
            <span className="text-sm">💾</span>
            <span>Save</span>
          </button>

          <button
            onClick={() => {
              sound.playClick();
              onOpenView('SETTINGS');
            }}
            className="flex items-center justify-center py-1.5 px-2 rounded-lg bg-stone-900 text-stone-300 hover:bg-stone-800 border border-stone-800 min-h-[44px] min-w-[36px] cursor-pointer"
            title="Options & Audio"
          >
            <span className="text-sm">⚙️</span>
          </button>
        </div>
      </div>
    </div>
  );
};
