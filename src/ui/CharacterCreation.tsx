/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { ATTRIBUTES, SKILLS } from '../core/constants';
import { AttributeKey, FactionId, HeroData, SkillKey } from '../core/types';
import { FACTIONS } from '../data/factions';
import { STRINGS } from '../data/strings';
import { sound } from '../audio/audioEngine';

interface CharacterCreationProps {
  onComplete: (hero: HeroData) => void;
  onCancel: () => void;
}

const BACKGROUND_OPTIONS = [
  {
    id: 'noble',
    title: 'Impoverished Landed Noble',
    desc: 'You were raised in a stone manor with riding horses and heraldic tutors. +1 Vigor, +1 Social, +200 Gold.',
    attrBonus: { vigor: 1, social: 1 } as Partial<Record<AttributeKey, number>>,
    skillBonus: { riding: 15, leadership: 15, oneHanded: 10 } as Partial<Record<SkillKey, number>>,
    goldBonus: 200,
  },
  {
    id: 'merchant',
    title: 'Caravan Guild Scion',
    desc: 'You accompanied trade caravans across desert tracks and river ferries. +1 Cunning, +1 Social, +300 Gold.',
    attrBonus: { cunning: 1, social: 1 } as Partial<Record<AttributeKey, number>>,
    skillBonus: { trade: 25, steward: 15, scouting: 10 } as Partial<Record<SkillKey, number>>,
    goldBonus: 300,
  },
  {
    id: 'soldier',
    title: 'Veteran Garrison Soldier',
    desc: 'You stood watch in border fortresses and fought in skirmishes. +1 Vigor, +1 Endurance, +50 Gold.',
    attrBonus: { vigor: 1, endurance: 1 } as Partial<Record<AttributeKey, number>>,
    skillBonus: { oneHanded: 15, polearm: 15, athletics: 15 } as Partial<Record<SkillKey, number>>,
    goldBonus: 50,
  },
  {
    id: 'hunter',
    title: 'Wildland Forester',
    desc: 'You tracked game through misty deep woods and know how to survive. +1 Control, +1 Cunning, +50 Gold.',
    attrBonus: { control: 1, cunning: 1 } as Partial<Record<AttributeKey, number>>,
    skillBonus: { bow: 25, scouting: 20, athletics: 10 } as Partial<Record<SkillKey, number>>,
    goldBonus: 50,
  },
  {
    id: 'smith',
    title: 'Apprentice Master Smith',
    desc: 'You learned the temper of iron, forgecraft, and logistics. +1 Vigor, +1 Intelligence, +100 Gold.',
    attrBonus: { vigor: 1, intelligence: 1 } as Partial<Record<AttributeKey, number>>,
    skillBonus: { twoHanded: 15, steward: 20, trade: 15 } as Partial<Record<SkillKey, number>>,
    goldBonus: 100,
  },
];

const YOUTH_OPTIONS = [
  {
    id: 'squire',
    title: 'Squire in Tournaments',
    desc: 'Couching lances, caring for warhorses, and absorbing martial tactics. +15 Riding, +15 Tactics.',
    skillBonus: { riding: 15, tactics: 15 } as Partial<Record<SkillKey, number>>,
  },
  {
    id: 'archery_line',
    title: 'Militia Bowman',
    desc: 'Defending palisades against raiders with rapid arrow volleys. +20 Archery, +10 Athletics.',
    skillBonus: { bow: 20, athletics: 10 } as Partial<Record<SkillKey, number>>,
  },
  {
    id: 'quartermaster',
    title: 'Camp Quartermaster',
    desc: 'Managing food rations, medicine, and luggage wagons. +15 Steward, +15 Medicine.',
    skillBonus: { steward: 15, medicine: 15 } as Partial<Record<SkillKey, number>>,
  },
  {
    id: 'scout',
    title: 'Outrider Scout',
    desc: 'Riding ahead of warbands to spot ambushes and find forage. +20 Scouting, +10 Riding.',
    skillBonus: { scouting: 20, riding: 10 } as Partial<Record<SkillKey, number>>,
  },
];

const MOTIVATION_OPTIONS = [
  {
    id: 'glory',
    title: 'Thirst for Sovereign Glory',
    desc: 'You seek to carve a royal name in the annals of history. +20 Renown, +10 Leadership.',
    renown: 20,
    skillBonus: { leadership: 10 } as Partial<Record<SkillKey, number>>,
  },
  {
    id: 'riches',
    title: 'Amassing Boundless Wealth',
    desc: 'You wish to control trade routes, fiefs, and merchant fleets. +250 Gold, +10 Trade.',
    gold: 250,
    skillBonus: { trade: 10 } as Partial<Record<SkillKey, number>>,
  },
  {
    id: 'vengeance',
    title: 'Vengeance Against Raiders',
    desc: 'Outlaws destroyed your ancestral home; you will bring steel to them. +15 Tactics, +10 One-Handed.',
    skillBonus: { tactics: 15, oneHanded: 10 } as Partial<Record<SkillKey, number>>,
  },
];

export const CharacterCreation: React.FC<CharacterCreationProps> = ({ onComplete, onCancel }) => {
  const [step, setStep] = useState<number>(1);
  const [selectedCulture, setSelectedCulture] = useState<FactionId>('vaeloria');
  const [selectedBg, setSelectedBg] = useState<string>('noble');
  const [selectedYouth, setSelectedYouth] = useState<string>('squire');
  const [selectedMotivation, setSelectedMotivation] = useState<string>('glory');

  const [name, setName] = useState<string>('Roland of the Marches');
  const [gender, setGender] = useState<'male' | 'female'>('male');
  const [symbolIndex, setSymbolIndex] = useState<number>(0);
  const [primaryColor, setPrimaryColor] = useState<string>('#1e3a8a');
  const [secondaryColor, setSecondaryColor] = useState<string>('#f59e0b');

  // Bonus points to allocate in step 5
  const [attrPoints, setAttrPoints] = useState<number>(2);
  const [skillPoints, setSkillPoints] = useState<number>(3);

  const [customAttrs, setCustomAttrs] = useState<Record<AttributeKey, number>>({
    vigor: 3,
    control: 3,
    endurance: 3,
    cunning: 3,
    social: 3,
    intelligence: 3,
  });

  const [customSkills, setCustomSkills] = useState<Record<SkillKey, number>>({
    oneHanded: 20,
    twoHanded: 15,
    polearm: 15,
    bow: 15,
    throwing: 10,
    riding: 20,
    athletics: 20,
    tactics: 15,
    scouting: 15,
    leadership: 20,
    trade: 15,
    steward: 15,
    medicine: 15,
  });

  const nextStep = () => {
    sound.playClick();
    if (step < 5) setStep(step + 1);
    else handleFinish();
  };

  const prevStep = () => {
    sound.playClick();
    if (step > 1) setStep(step - 1);
    else onCancel();
  };

  const addAttr = (key: AttributeKey) => {
    if (attrPoints > 0) {
      sound.playClick();
      setAttrPoints(attrPoints - 1);
      setCustomAttrs({ ...customAttrs, [key]: customAttrs[key] + 1 });
    }
  };

  const addSkill = (key: SkillKey) => {
    if (skillPoints > 0) {
      sound.playClick();
      setSkillPoints(skillPoints - 1);
      setCustomSkills({ ...customSkills, [key]: customSkills[key] + 10 });
    }
  };

  const handleFinish = () => {
    sound.playCoin();
    const bg = BACKGROUND_OPTIONS.find((b) => b.id === selectedBg) || BACKGROUND_OPTIONS[0];
    const motivation = MOTIVATION_OPTIONS.find((m) => m.id === selectedMotivation) || MOTIVATION_OPTIONS[0];

    const finalAttrs = { ...customAttrs };
    if (bg.attrBonus) {
      for (const [k, v] of Object.entries(bg.attrBonus)) {
        finalAttrs[k as AttributeKey] = (finalAttrs[k as AttributeKey] || 3) + (v || 0);
      }
    }

    const finalSkills = { ...customSkills };
    if (bg.skillBonus) {
      for (const [k, v] of Object.entries(bg.skillBonus)) {
        finalSkills[k as SkillKey] = (finalSkills[k as SkillKey] || 15) + (v || 0);
      }
    }

    const youth = YOUTH_OPTIONS.find((y) => y.id === selectedYouth);
    if (youth?.skillBonus) {
      for (const [k, v] of Object.entries(youth.skillBonus)) {
        finalSkills[k as SkillKey] = (finalSkills[k as SkillKey] || 15) + (v || 0);
      }
    }

    if (motivation.skillBonus) {
      for (const [k, v] of Object.entries(motivation.skillBonus)) {
        finalSkills[k as SkillKey] = (finalSkills[k as SkillKey] || 15) + (v || 0);
      }
    }

    const hero: HeroData = {
      id: 'hero_player',
      name: name.trim() || 'Roland of the Marches',
      culture: selectedCulture,
      backgroundOrigin: bg.title,
      gender,
      hp: 100,
      maxHp: 100,
      gold: 500 + bg.goldBonus + (motivation.gold || 0),
      renown: 10 + (motivation.renown || 0),
      honor: 10,
      attributes: finalAttrs,
      skills: finalSkills,
      attributePoints: attrPoints,
      skillPoints: skillPoints,
      equipment: {
        head: 'helm_kettle_hat',
        body: 'body_gambeson',
        hands: 'gloves_leather',
        feet: 'boots_leather',
        weapon1: 'wpn_arming_sword',
        shieldOrArrows: 'wpn_heater_shield',
        mount: 'horse_sumpter',
      },
      inventory: [
        { id: 'food_grain', count: 4 },
        { id: 'food_meat', count: 2 },
        { id: 'trade_wool', count: 2 },
      ],
      bannerHeraldry: {
        symbolIndex,
        bgPatternIndex: 0,
        primaryColor,
        secondaryColor,
      },
    };

    onComplete(hero);
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-stone-950 text-stone-100 p-4 md:p-6 overflow-y-auto">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-stone-800 pb-3 mb-4">
        <div>
          <h2 className="text-xl md:text-2xl font-bold font-display text-amber-200">
            {STRINGS.creation.title}
          </h2>
          <p className="text-xs text-stone-400">Step {step} of 5</p>
        </div>
        <button
          onClick={onCancel}
          className="text-xs text-stone-400 hover:text-stone-200 py-1.5 px-3 rounded border border-stone-800 min-h-[44px]"
        >
          {STRINGS.creation.back}
        </button>
      </div>

      {/* Content Step Card */}
      <div className="flex-1 flex flex-col max-w-2xl mx-auto w-full my-auto pb-2">
        {step === 1 && (
          <div className="space-y-3">
            <h3 className="text-lg font-semibold text-amber-300 font-display">
              {STRINGS.creation.stepCulture}
            </h3>
            <p className="text-xs text-stone-300">
              Your homeland shapes your customs, cavalry tactics, and initial relations with the great lords.
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
              {Object.values(FACTIONS).map((f) => (
                <button
                  key={f.id}
                  onClick={() => {
                    sound.playClick();
                    setSelectedCulture(f.id);
                    setPrimaryColor(f.primaryColor);
                    setSecondaryColor(f.secondaryColor);
                  }}
                  className={`p-3.5 rounded-lg border text-left transition-all min-h-[64px] cursor-pointer ${
                    selectedCulture === f.id
                      ? 'border-amber-400 bg-stone-900 shadow-md shadow-amber-950/40'
                      : 'border-stone-800 bg-stone-900/60 hover:border-stone-700'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="font-bold text-sm text-stone-100">{f.name}</span>
                    <span
                      className="w-4 h-4 rounded-full border border-stone-500"
                      style={{ backgroundColor: f.primaryColor }}
                    />
                  </div>
                  <p className="text-xs text-amber-400/90 font-medium mt-1">
                    {f.culturalTrait.title}: {f.culturalTrait.description}
                  </p>
                  <p className="text-[11px] text-stone-400 mt-1 line-clamp-2">{f.lore}</p>
                </button>
              ))}
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-4">
            <h3 className="text-lg font-semibold text-amber-300 font-display">
              {STRINGS.creation.stepOrigin}
            </h3>
            <p className="text-xs text-stone-300">
              Who were your kin, and what station in medieval society did you inherit?
            </p>
            <div className="space-y-2.5">
              {BACKGROUND_OPTIONS.map((bg) => (
                <button
                  key={bg.id}
                  onClick={() => {
                    sound.playClick();
                    setSelectedBg(bg.id);
                  }}
                  className={`w-full p-3.5 rounded-lg border text-left transition-all min-h-[56px] cursor-pointer ${
                    selectedBg === bg.id
                      ? 'border-amber-400 bg-stone-900 shadow-md shadow-amber-950/40'
                      : 'border-stone-800 bg-stone-900/60 hover:border-stone-700'
                  }`}
                >
                  <span className="font-bold text-sm text-stone-100">{bg.title}</span>
                  <p className="text-xs text-stone-300 mt-0.5">{bg.desc}</p>
                </button>
              ))}
            </div>
          </div>
        )}

        {step === 3 && (
          <div className="space-y-4">
            <h3 className="text-lg font-semibold text-amber-300 font-display">
              {STRINGS.creation.stepYouth}
            </h3>
            <p className="text-xs text-stone-300">
              In your coming of age, what trade or martial discipline took hold of your youth?
            </p>
            <div className="space-y-2.5">
              {YOUTH_OPTIONS.map((y) => (
                <button
                  key={y.id}
                  onClick={() => {
                    sound.playClick();
                    setSelectedYouth(y.id);
                  }}
                  className={`w-full p-3.5 rounded-lg border text-left transition-all min-h-[56px] cursor-pointer ${
                    selectedYouth === y.id
                      ? 'border-amber-400 bg-stone-900 shadow-md shadow-amber-950/40'
                      : 'border-stone-800 bg-stone-900/60 hover:border-stone-700'
                  }`}
                >
                  <span className="font-bold text-sm text-stone-100">{y.title}</span>
                  <p className="text-xs text-stone-300 mt-0.5">{y.desc}</p>
                </button>
              ))}
            </div>
          </div>
        )}

        {step === 4 && (
          <div className="space-y-4">
            <h3 className="text-lg font-semibold text-amber-300 font-display">
              {STRINGS.creation.stepMotivation}
            </h3>
            <p className="text-xs text-stone-300">
              What driving purpose compelled you to strap on armor and gather a mercenary host?
            </p>
            <div className="space-y-2.5">
              {MOTIVATION_OPTIONS.map((m) => (
                <button
                  key={m.id}
                  onClick={() => {
                    sound.playClick();
                    setSelectedMotivation(m.id);
                  }}
                  className={`w-full p-3.5 rounded-lg border text-left transition-all min-h-[56px] cursor-pointer ${
                    selectedMotivation === m.id
                      ? 'border-amber-400 bg-stone-900 shadow-md shadow-amber-950/40'
                      : 'border-stone-800 bg-stone-900/60 hover:border-stone-700'
                  }`}
                >
                  <span className="font-bold text-sm text-stone-100">{m.title}</span>
                  <p className="text-xs text-stone-300 mt-0.5">{m.desc}</p>
                </button>
              ))}
            </div>
          </div>
        )}

        {step === 5 && (
          <div className="space-y-4">
            <h3 className="text-lg font-semibold text-amber-300 font-display">
              {STRINGS.creation.stepCustomization}
            </h3>

            {/* Name & Gender */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-stone-400 mb-1">
                  {STRINGS.creation.namePrompt}
                </label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="w-full bg-stone-900 border border-stone-700 rounded p-2.5 text-sm text-amber-100 font-semibold focus:outline-none focus:border-amber-400"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-stone-400 mb-1">Honorific Title</label>
                <div className="flex gap-2">
                  <button
                    onClick={() => setGender('male')}
                    className={`flex-1 py-2 rounded text-xs font-semibold border min-h-[44px] ${
                      gender === 'male'
                        ? 'bg-amber-800/80 border-amber-400 text-amber-100'
                        : 'bg-stone-900 border-stone-800 text-stone-400'
                    }`}
                  >
                    Lord
                  </button>
                  <button
                    onClick={() => setGender('female')}
                    className={`flex-1 py-2 rounded text-xs font-semibold border min-h-[44px] ${
                      gender === 'female'
                        ? 'bg-amber-800/80 border-amber-400 text-amber-100'
                        : 'bg-stone-900 border-stone-800 text-stone-400'
                    }`}
                  >
                    Lady
                  </button>
                </div>
              </div>
            </div>

            {/* Heraldic Emblem Selector */}
            <div className="p-3 bg-stone-900/80 rounded border border-stone-800">
              <span className="block text-xs font-semibold text-amber-300 mb-2">
                Warband Banner Heraldry
              </span>
              <div className="flex items-center gap-3">
                <div
                  className="w-16 h-20 rounded border-2 flex items-center justify-center shadow"
                  style={{
                    backgroundColor: primaryColor,
                    borderColor: secondaryColor,
                  }}
                >
                  <span className="text-2xl font-bold" style={{ color: secondaryColor }}>
                    {['👑', '⚔️', '🦁', '🦅', '🐺', '🛡️'][symbolIndex]}
                  </span>
                </div>
                <div className="flex-1 space-y-2">
                  <div className="flex gap-1.5 flex-wrap">
                    {['👑', '⚔️', '🦁', '🦅', '🐺', '🛡️'].map((sym, idx) => (
                      <button
                        key={idx}
                        onClick={() => {
                          sound.playClick();
                          setSymbolIndex(idx);
                        }}
                        className={`w-9 h-9 rounded text-base flex items-center justify-center border min-h-[36px] ${
                          symbolIndex === idx ? 'border-amber-400 bg-stone-800' : 'border-stone-800'
                        }`}
                      >
                        {sym}
                      </button>
                    ))}
                  </div>
                  <div className="flex items-center gap-2 text-xs text-stone-400">
                    <span>Colors:</span>
                    <input
                      type="color"
                      value={primaryColor}
                      onChange={(e) => setPrimaryColor(e.target.value)}
                      className="w-7 h-7 rounded border border-stone-700 bg-transparent cursor-pointer"
                    />
                    <input
                      type="color"
                      value={secondaryColor}
                      onChange={(e) => setSecondaryColor(e.target.value)}
                      className="w-7 h-7 rounded border border-stone-700 bg-transparent cursor-pointer"
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Points Allocator */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
              <div className="p-3 bg-stone-900/60 rounded border border-stone-800">
                <div className="flex justify-between font-bold text-amber-300 mb-2">
                  <span>Attributes</span>
                  <span className="tabular-nums">Points: {attrPoints}</span>
                </div>
                <div className="space-y-1.5">
                  {(Object.keys(ATTRIBUTES) as AttributeKey[]).map((k) => (
                    <div key={k} className="flex items-center justify-between">
                      <span className="text-stone-300">{ATTRIBUTES[k].name}</span>
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-amber-100 tabular-nums">
                          {customAttrs[k]}
                        </span>
                        <button
                          onClick={() => addAttr(k)}
                          disabled={attrPoints <= 0}
                          className="w-6 h-6 rounded bg-stone-800 hover:bg-stone-700 disabled:opacity-30 border border-stone-700 text-amber-300 font-bold"
                        >
                          +
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              <div className="p-3 bg-stone-900/60 rounded border border-stone-800">
                <div className="flex justify-between font-bold text-amber-300 mb-2">
                  <span>Core Skills</span>
                  <span className="tabular-nums">Points: {skillPoints}</span>
                </div>
                <div className="space-y-1.5 max-h-48 overflow-y-auto pr-1">
                  {(Object.keys(SKILLS) as SkillKey[]).slice(0, 8).map((k) => (
                    <div key={k} className="flex items-center justify-between">
                      <span className="text-stone-300">{SKILLS[k].name}</span>
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-amber-100 tabular-nums">
                          {customSkills[k]}
                        </span>
                        <button
                          onClick={() => addSkill(k)}
                          disabled={skillPoints <= 0}
                          className="w-6 h-6 rounded bg-stone-800 hover:bg-stone-700 disabled:opacity-30 border border-stone-700 text-amber-300 font-bold"
                        >
                          +
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Navigation Footer */}
      <div className="flex items-center justify-between border-t border-stone-800 pt-3 mt-4 max-w-2xl mx-auto w-full">
        <button
          onClick={prevStep}
          className="py-2.5 px-5 rounded border border-stone-700 hover:bg-stone-800 text-stone-300 font-semibold text-xs min-h-[44px] cursor-pointer"
        >
          {step === 1 ? 'Cancel' : STRINGS.creation.back}
        </button>

        <button
          onClick={nextStep}
          className="py-2.5 px-6 rounded bg-gradient-to-r from-amber-700 to-amber-600 hover:from-amber-600 hover:to-amber-500 text-amber-100 font-bold text-xs tracking-wider border border-amber-500/40 shadow min-h-[44px] cursor-pointer"
        >
          {step === 5 ? STRINGS.creation.finish : STRINGS.creation.next}
        </button>
      </div>
    </div>
  );
};
