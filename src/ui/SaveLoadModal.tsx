/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useEffect, useState } from 'react';
import { SaveGameMetadata } from '../core/types';
import { STRINGS } from '../data/strings';
import { SaveManager } from '../save/saveManager';
import { sound } from '../audio/audioEngine';

interface SaveLoadModalProps {
  onClose: () => void;
  onSaveToSlot: (slot: number) => Promise<boolean>;
  onLoadFromSlot: (slot: number) => Promise<boolean>;
}

export const SaveLoadModal: React.FC<SaveLoadModalProps> = ({
  onClose,
  onSaveToSlot,
  onLoadFromSlot,
}) => {
  const [saves, setSaves] = useState<Record<number, SaveGameMetadata | null>>({
    0: null,
    1: null,
    2: null,
    3: null,
  });
  const [notification, setNotification] = useState<string | null>(null);

  const refreshSaves = async () => {
    const list = await SaveManager.listSaves();
    setSaves(list);
  };

  useEffect(() => {
    refreshSaves();
  }, []);

  const handleSave = async (slot: number) => {
    sound.playClick();
    const success = await onSaveToSlot(slot);
    if (success) {
      sound.playCoin();
      setNotification(STRINGS.saveLoad.saveSuccess);
      await refreshSaves();
      setTimeout(() => setNotification(null), 3000);
    }
  };

  const handleLoad = async (slot: number) => {
    sound.playClick();
    const success = await onLoadFromSlot(slot);
    if (success) {
      setNotification(STRINGS.saveLoad.loadSuccess);
      setTimeout(() => {
        onClose();
      }, 500);
    }
  };

  const handleDelete = async (slot: number) => {
    sound.playClick();
    await SaveManager.delete(slot);
    await refreshSaves();
  };

  const slots = [
    { id: 0, title: STRINGS.saveLoad.autosaveSlot, isAutosave: true },
    { id: 1, title: STRINGS.saveLoad.slot1, isAutosave: false },
    { id: 2, title: STRINGS.saveLoad.slot2, isAutosave: false },
    { id: 3, title: STRINGS.saveLoad.slot3, isAutosave: false },
  ];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-2 sm:p-4 select-none overflow-y-auto">
      <div className="w-full max-w-lg max-h-[96vh] overflow-y-auto bg-stone-950 border border-stone-800 rounded-xl p-3.5 sm:p-5 shadow-2xl text-stone-100 flex flex-col space-y-3 sm:space-y-4">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-stone-800 pb-3">
          <h3 className="text-xl font-bold font-display text-amber-200">
            {STRINGS.saveLoad.title}
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

        {notification && (
          <div className="p-2.5 rounded bg-emerald-950/80 border border-emerald-500/60 text-emerald-200 text-xs text-center">
            {notification}
          </div>
        )}

        {/* Save Slots List */}
        <div className="space-y-3">
          {slots.map((s) => {
            const meta = saves[s.id];

            return (
              <div
                key={s.id}
                className="p-3.5 rounded-lg bg-stone-900/80 border border-stone-800 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs"
              >
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-bold text-amber-300 text-sm">{s.title}</span>
                    {s.isAutosave && (
                      <span className="text-[10px] px-1.5 py-0.2 rounded bg-stone-950 text-stone-400 border border-stone-800">
                        Auto
                      </span>
                    )}
                  </div>

                  {meta ? (
                    <div className="text-stone-300 text-[11px] mt-1 space-y-0.5">
                      <p>
                        Commander: <strong className="text-stone-100">{meta.heroName}</strong> (
                        {meta.heroCulture.toUpperCase()})
                      </p>
                      <p className="text-stone-400">
                        Day {meta.day} · {meta.partySize} Warriors · {meta.gold} Gold
                      </p>
                      <p className="text-[10px] text-stone-500">
                        {new Date(meta.timestamp).toLocaleString()}
                      </p>
                    </div>
                  ) : (
                    <p className="text-stone-500 italic mt-1">{STRINGS.saveLoad.emptySlot}</p>
                  )}
                </div>

                <div className="flex items-center gap-2">
                  {!s.isAutosave && (
                    <button
                      onClick={() => handleSave(s.id)}
                      className="py-1.5 px-3 rounded bg-amber-700 hover:bg-amber-600 text-amber-100 font-bold min-h-[40px] cursor-pointer"
                    >
                      Save
                    </button>
                  )}

                  {meta && (
                    <button
                      onClick={() => handleLoad(s.id)}
                      className="py-1.5 px-3 rounded bg-stone-800 hover:bg-stone-700 border border-stone-600 text-stone-200 font-bold min-h-[40px] cursor-pointer"
                    >
                      Load
                    </button>
                  )}

                  {meta && !s.isAutosave && (
                    <button
                      onClick={() => handleDelete(s.id)}
                      className="py-1 px-2 rounded bg-stone-900 hover:bg-red-950 border border-stone-800 text-stone-400 hover:text-red-300 min-h-[40px] cursor-pointer"
                      title="Wipe"
                    >
                      🗑️
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
