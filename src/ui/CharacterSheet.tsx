/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React from 'react';
import { ATTRIBUTES, SKILLS } from '../core/constants';
import { AttributeKey, HeroData, SkillKey } from '../core/types';
import { FACTIONS } from '../data/factions';
import { STRINGS } from '../data/strings';
import { sound } from '../audio/audioEngine';

interface CharacterSheetProps {
  hero: HeroData;
  onClose: () => void;
  onAllocateAttribute: (attr: AttributeKey) => void;
  onAllocateSkill: (skill: SkillKey) => void;
}

export const CharacterSheet: React.FC<CharacterSheetProps> = ({
  hero,
  onClose,
  onAllocateAttribute,
  onAllocateSkill,
}) => {
  const faction = FACTIONS[hero.culture] || FACTIONS.vaeloria;

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-stone-950 text-stone-100 p-3 md:p-6 select-none overflow-y-auto">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-stone-800 pb-3 mb-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl md:text-2xl font-bold font-display text-amber-200">
              {hero.name}
            </h2>
            <span className="text-xs px-2 py-0.5 rounded bg-stone-900 border border-stone-700 text-amber-300 font-semibold">
              {hero.gender === 'male' ? 'Lord' : 'Lady'} of Caladria
            </span>
          </div>
          <p className="text-xs text-stone-400 mt-0.5">
            Origin: {hero.backgroundOrigin} · Heritage: {faction.name} · Renown:{' '}
            <strong className="text-amber-300 font-mono">{hero.renown}</strong> · Honor:{' '}
            <strong className="text-emerald-400 font-mono">{hero.honor}</strong>
          </p>
        </div>

        <button
          onClick={() => {
            sound.playClick();
            onClose();
          }}
          className="py-2 px-4 rounded bg-stone-800 hover:bg-stone-700 text-stone-200 border border-stone-700 text-xs font-bold min-h-[44px] cursor-pointer"
        >
          Close Sheet
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 flex-1">
        {/* Left Column: Attributes (5 cols) */}
        <div className="lg:col-span-5 space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold text-amber-400 uppercase tracking-wider">
              Primary Attributes
            </span>
            {hero.attributePoints > 0 && (
              <span className="text-xs bg-amber-950 border border-amber-600/60 text-amber-300 px-2 py-0.5 rounded font-bold animate-pulse">
                +{hero.attributePoints} Points Available
              </span>
            )}
          </div>

          <div className="space-y-2">
            {(Object.keys(ATTRIBUTES) as AttributeKey[]).map((key) => {
              const def = ATTRIBUTES[key];
              const val = hero.attributes[key] || 3;

              return (
                <div
                  key={key}
                  className="p-3 rounded-lg bg-stone-900/80 border border-stone-800 flex items-center justify-between"
                >
                  <div className="flex-1 pr-3">
                    <span className="font-bold text-sm text-stone-100">{def.name}</span>
                    <p className="text-[11px] text-stone-400 mt-0.5">{def.description}</p>
                  </div>

                  <div className="flex items-center gap-2">
                    <span className="text-base font-bold font-mono text-amber-200 tabular-nums">
                      {val}
                    </span>
                    {hero.attributePoints > 0 && (
                      <button
                        onClick={() => {
                          sound.playClick();
                          onAllocateAttribute(key);
                        }}
                        className="w-7 h-7 rounded bg-amber-700 hover:bg-amber-600 border border-amber-500/50 text-amber-100 font-bold text-sm flex items-center justify-center cursor-pointer min-h-[36px] min-w-[36px]"
                      >
                        +
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {/* Cultural Perk Card */}
          <div className="p-3.5 rounded-lg bg-stone-900/90 border border-stone-800 mt-4">
            <span className="text-xs font-bold text-amber-400 uppercase tracking-wider block mb-1">
              Cultural Heritage: {faction.culturalTrait.title}
            </span>
            <p className="text-xs text-stone-300">{faction.culturalTrait.description}</p>
          </div>
        </div>

        {/* Right Column: 12 Skills (7 cols) */}
        <div className="lg:col-span-7 space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold text-amber-400 uppercase tracking-wider">
              Martial & Tactical Skills
            </span>
            {hero.skillPoints > 0 && (
              <span className="text-xs bg-amber-950 border border-amber-600/60 text-amber-300 px-2 py-0.5 rounded font-bold animate-pulse">
                +{hero.skillPoints} Skill Points Available
              </span>
            )}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 max-h-[70vh] overflow-y-auto pr-1">
            {(Object.keys(SKILLS) as SkillKey[]).map((key) => {
              const def = SKILLS[key];
              const val = hero.skills[key] || 15;

              return (
                <div
                  key={key}
                  className="p-3 rounded-lg bg-stone-900/80 border border-stone-800 flex flex-col justify-between"
                >
                  <div className="flex items-center justify-between mb-1">
                    <span className="font-bold text-xs text-stone-100">{def.name}</span>
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs font-mono font-bold text-amber-200 tabular-nums">
                        {val}
                      </span>
                      {hero.skillPoints > 0 && (
                        <button
                          onClick={() => {
                            sound.playClick();
                            onAllocateSkill(key);
                          }}
                          className="w-6 h-6 rounded bg-amber-700 hover:bg-amber-600 border border-amber-500/50 text-amber-100 font-bold text-xs flex items-center justify-center cursor-pointer min-h-[32px] min-w-[32px]"
                        >
                          +
                        </button>
                      )}
                    </div>
                  </div>

                  <p className="text-[10px] text-stone-400 line-clamp-2">{def.description}</p>

                  {/* Skill level bar */}
                  <div className="w-full bg-stone-950 h-1.5 rounded-full mt-2 overflow-hidden border border-stone-800">
                    <div
                      className="bg-amber-500 h-full rounded-full transition-all"
                      style={{ width: `${Math.min(100, (val / 100) * 100)}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};
