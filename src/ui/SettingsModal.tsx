/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React from 'react';
import { GameSettings } from '../core/types';
import { STRINGS } from '../data/strings';
import { sound } from '../audio/audioEngine';

interface SettingsModalProps {
  settings: GameSettings;
  onUpdateSettings: (newSettings: Partial<GameSettings>) => void;
  onClose: () => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  settings,
  onUpdateSettings,
  onClose,
}) => {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-2 sm:p-4 select-none overflow-y-auto">
      <div className="w-full max-w-md max-h-[96vh] overflow-y-auto bg-stone-950 border border-stone-800 rounded-xl p-3.5 sm:p-5 shadow-2xl text-stone-100 flex flex-col space-y-3 sm:space-y-4">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-stone-800 pb-3">
          <h3 className="text-xl font-bold font-display text-amber-200">
            {STRINGS.settingsStrings.title}
          </h3>
          <button
            onClick={() => {
              sound.playClick();
              onClose();
            }}
            className="text-xs text-stone-400 hover:text-stone-200 py-1.5 px-3 rounded border border-stone-800 min-h-[36px]"
          >
            Close
          </button>
        </div>

        <div className="space-y-4 text-xs">
          {/* Audio Mute Toggle */}
          <div className="flex items-center justify-between p-3 rounded bg-stone-900/80 border border-stone-800">
            <div>
              <span className="font-bold text-stone-200 block">
                {STRINGS.settingsStrings.audioMute}
              </span>
              <span className="text-[11px] text-stone-400">
                Procedural WebAudio sound effects
              </span>
            </div>
            <button
              onClick={() => {
                sound.playClick();
                const nextMuted = !settings.soundMuted;
                sound.setMuted(nextMuted);
                onUpdateSettings({ soundMuted: nextMuted });
              }}
              className={`py-1.5 px-4 rounded text-xs font-bold border min-h-[44px] cursor-pointer ${
                settings.soundMuted
                  ? 'bg-red-950 border-red-700 text-red-300'
                  : 'bg-emerald-950 border-emerald-700 text-emerald-300'
              }`}
            >
              {settings.soundMuted ? 'Muted' : 'Sound On'}
            </button>
          </div>

          {/* Volume Slider */}
          <div className="p-3 rounded bg-stone-900/80 border border-stone-800 space-y-2">
            <div className="flex justify-between font-bold text-stone-200">
              <span>{STRINGS.settingsStrings.masterVolume}</span>
              <span className="font-mono text-amber-300">
                {Math.round(settings.masterVolume * 100)}%
              </span>
            </div>
            <input
              type="range"
              min="0"
              max="1"
              step="0.05"
              value={settings.masterVolume}
              onChange={(e) => {
                const vol = parseFloat(e.target.value);
                sound.setVolume(vol);
                onUpdateSettings({ masterVolume: vol });
              }}
              className="w-full accent-amber-500 cursor-pointer h-2 bg-stone-800 rounded"
            />
          </div>

          {/* DPR Cap Toggle */}
          <div className="flex items-center justify-between p-3 rounded bg-stone-900/80 border border-stone-800">
            <div>
              <span className="font-bold text-stone-200 block">
                {STRINGS.settingsStrings.dprCap}
              </span>
              <span className="text-[11px] text-stone-400">
                {settings.dprCap >= 2 ? 'Crisp Retina Rendering (2.0x)' : 'High Performance (1.0x)'}
              </span>
            </div>
            <button
              onClick={() => {
                sound.playClick();
                onUpdateSettings({ dprCap: settings.dprCap >= 2 ? 1 : 2 });
              }}
              className="py-1.5 px-3 rounded bg-stone-800 border border-stone-700 text-amber-200 font-bold min-h-[44px] cursor-pointer"
            >
              {settings.dprCap >= 2 ? '2.0x DPR' : '1.0x DPR'}
            </button>
          </div>

          {/* Fog of War Toggle */}
          <div className="flex items-center justify-between p-3 rounded bg-stone-900/80 border border-stone-800">
            <div>
              <span className="font-bold text-stone-200 block">
                {STRINGS.settingsStrings.fogOfWar}
              </span>
              <span className="text-[11px] text-stone-400">
                Explore realm tiles dynamically
              </span>
            </div>
            <button
              onClick={() => {
                sound.playClick();
                onUpdateSettings({ fogOfWar: !settings.fogOfWar });
              }}
              className={`py-1.5 px-3 rounded border text-xs font-bold min-h-[44px] cursor-pointer ${
                settings.fogOfWar
                  ? 'bg-amber-950 border-amber-600 text-amber-200'
                  : 'bg-stone-900 border-stone-700 text-stone-400'
              }`}
            >
              {settings.fogOfWar ? 'Enabled' : 'Revealed'}
            </button>
          </div>
        </div>

        <button
          onClick={() => {
            sound.playClick();
            onClose();
          }}
          className="w-full py-3 px-4 rounded bg-stone-800 hover:bg-stone-700 text-stone-100 font-bold text-xs min-h-[44px] cursor-pointer"
        >
          {STRINGS.settingsStrings.close}
        </button>
      </div>
    </div>
  );
};
