/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { STRINGS } from '../data/strings';
import { sound } from '../audio/audioEngine';

interface TutorialModalProps {
  onClose: () => void;
}

export const TutorialModal: React.FC<TutorialModalProps> = ({ onClose }) => {
  const [stepIndex, setStepIndex] = useState<number>(0);

  const steps = [
    {
      title: '1. Marching Upon Caladria',
      icon: '🗺️',
      text: STRINGS.tutorial.step1,
      hint: 'Single tap anywhere on the terrain to march. Drag with finger to pan the camera view. Roads grant a +20% travel speed bonus!',
    },
    {
      title: '2. Settlements & Mercantile Trade',
      icon: '🏰',
      text: STRINGS.tutorial.step2,
      hint: 'Towns have bustling markets with regional surpluses (e.g. Ironmark sells cheap Iron Ore, Sunreach produces cheap Spices and Velvet). Buy low and sell high across realms!',
    },
    {
      title: '3. Hostile Encounters & Tactics',
      icon: '⚔️',
      text: STRINGS.tutorial.step3,
      hint: 'When meeting bandits or lords, compare relative numbers. If outmatched, you can offer a purse of gold or sound retreat before battle begins.',
    },
    {
      title: '4. Warband Roster & Promotions',
      icon: '🛡️',
      text: STRINGS.tutorial.step4,
      hint: 'Keep enough food (grain, bread, meat) and gold to pay midnight wages. Troops that survive battles gain experience to be promoted into elite knights, huscarls, or rangers.',
    },
    {
      title: '5. Time & Campaign Mastery',
      icon: '⏳',
      text: STRINGS.tutorial.step5,
      hint: 'Whenever your host stops marching, the world automatically pauses, letting you strategize without rushing. Fast forward with 2x or 4x during long journeys.',
    },
  ];

  const current = steps[stepIndex];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-4 select-none">
      <div className="w-full max-w-md bg-stone-950 border border-stone-800 rounded-xl p-5 shadow-2xl text-stone-100 flex flex-col space-y-4">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-stone-800 pb-3">
          <div className="flex items-center gap-2">
            <span className="text-xl">{current.icon}</span>
            <h3 className="text-lg font-bold font-display text-amber-200">
              {current.title}
            </h3>
          </div>
          <span className="text-xs text-stone-500 font-mono">
            {stepIndex + 1}/{steps.length}
          </span>
        </div>

        {/* Content */}
        <div className="space-y-3 py-2">
          <p className="text-xs md:text-sm text-stone-200 leading-relaxed font-serif">
            {current.text}
          </p>
          <div className="p-3 rounded bg-stone-900/90 border border-amber-600/30 text-xs text-amber-300/90">
            <strong>Command Tip:</strong> {current.hint}
          </div>
        </div>

        {/* Navigation Buttons */}
        <div className="flex items-center justify-between pt-2 border-t border-stone-800">
          <button
            onClick={() => {
              sound.playClick();
              if (stepIndex > 0) setStepIndex(stepIndex - 1);
              else onClose();
            }}
            className="py-2 px-4 rounded border border-stone-700 hover:bg-stone-800 text-stone-300 text-xs font-semibold min-h-[44px] cursor-pointer"
          >
            {stepIndex === 0 ? 'Skip Primer' : 'Previous'}
          </button>

          <button
            onClick={() => {
              sound.playClick();
              if (stepIndex < steps.length - 1) {
                setStepIndex(stepIndex + 1);
              } else {
                onClose();
              }
            }}
            className="py-2 px-5 rounded bg-amber-700 hover:bg-amber-600 text-amber-100 font-bold text-xs min-h-[44px] cursor-pointer"
          >
            {stepIndex === steps.length - 1 ? STRINGS.tutorial.understood : 'Next Tip →'}
          </button>
        </div>
      </div>
    </div>
  );
};
