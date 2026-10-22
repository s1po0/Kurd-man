/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useEffect, useRef } from 'react';
import { STRINGS } from '../data/strings';
import { sound } from '../audio/audioEngine';

interface TitleScreenProps {
  onStartNewGame: () => void;
  onResumeGame?: () => void;
  hasSaveGame: boolean;
  onOpenSettings: () => void;
  onOpenTutorial: () => void;
}

export const TitleScreen: React.FC<TitleScreenProps> = ({
  onStartNewGame,
  onResumeGame,
  hasSaveGame,
  onOpenSettings,
  onOpenTutorial,
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  // Background animated embers/particles
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animId: number;
    const particles: { x: number; y: number; r: number; vy: number; vx: number; alpha: number }[] = [];

    const resize = () => {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    };
    resize();
    window.addEventListener('resize', resize);

    for (let i = 0; i < 40; i++) {
      particles.push({
        x: Math.random() * canvas.width,
        y: Math.random() * canvas.height,
        r: Math.random() * 2.5 + 1,
        vy: -(Math.random() * 0.8 + 0.3),
        vx: (Math.random() - 0.5) * 0.4,
        alpha: Math.random() * 0.7 + 0.3,
      });
    }

    const render = () => {
      ctx.fillStyle = '#090807';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      // Ambient radial lighting behind crest
      const cx = canvas.width / 2;
      const cy = canvas.height * 0.38;
      const radGrad = ctx.createRadialGradient(cx, cy, 20, cx, cy, Math.max(cx, cy) * 0.8);
      radGrad.addColorStop(0, 'rgba(180, 83, 9, 0.22)');
      radGrad.addColorStop(0.5, 'rgba(120, 53, 15, 0.08)');
      radGrad.addColorStop(1, 'rgba(0, 0, 0, 0)');
      ctx.fillStyle = radGrad;
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      // Render drifting golden embers
      for (const p of particles) {
        p.y += p.vy;
        p.x += p.vx;
        if (p.y < 0) {
          p.y = canvas.height + 10;
          p.x = Math.random() * canvas.width;
        }
        ctx.fillStyle = `rgba(245, 158, 11, ${p.alpha})`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fill();
      }

      animId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animId);
      window.removeEventListener('resize', resize);
    };
  }, []);

  // Keyboard shortcut listener for Enter / Space
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        sound.playClick();
        onStartNewGame();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onStartNewGame]);

  return (
    <div className="relative w-full h-full flex flex-col items-center justify-between p-6 select-none overflow-hidden">
      <canvas ref={canvasRef} className="absolute inset-0 pointer-events-none" />

      {/* Top Banner Heraldry Lockup */}
      <div className="relative z-10 flex flex-col items-center mt-6 text-center max-w-xl">
        {/* Heraldic Crown / Sword Emblem */}
        <div className="mb-3 text-amber-500 drop-shadow-[0_0_15px_rgba(245,158,11,0.5)]">
          <svg className="w-16 h-16 md:w-20 md:h-20" viewBox="0 0 64 64" fill="none">
            <path
              d="M32 4L38 20L54 22L42 34L46 50L32 42L18 50L22 34L10 22L26 20L32 4Z"
              fill="url(#crownGold)"
              stroke="#fbbf24"
              strokeWidth="2"
            />
            <circle cx="32" cy="28" r="4" fill="#1e1b4b" stroke="#f59e0b" strokeWidth="1.5" />
            <path d="M32 20V44M24 32H40" stroke="#fef08a" strokeWidth="2.5" strokeLinecap="round" />
            <defs>
              <linearGradient id="crownGold" x1="10" y1="4" x2="54" y2="50" gradientUnits="userSpaceOnUse">
                <stop stopColor="#f59e0b" />
                <stop offset="0.5" stopColor="#d97706" />
                <stop offset="1" stopColor="#78350f" />
              </linearGradient>
            </defs>
          </svg>
        </div>

        <h1 className="text-4xl md:text-6xl font-extrabold tracking-wider text-amber-100 font-display uppercase drop-shadow-[0_4px_12px_rgba(0,0,0,0.8)]">
          {STRINGS.gameTitle}
        </h1>
        <p className="text-sm md:text-base font-semibold tracking-widest text-amber-400/90 uppercase mt-1">
          {STRINGS.subtitle}
        </p>
        <div className="w-48 h-0.5 bg-gradient-to-r from-transparent via-amber-500/60 to-transparent my-3" />
        <p className="text-xs md:text-sm text-stone-400 max-w-md italic font-serif">
          &ldquo;{STRINGS.tagline}&rdquo;
        </p>
      </div>

      {/* Menu Actions Cluster */}
      <div className="relative z-10 flex flex-col gap-3.5 w-full max-w-xs mb-8">
        {hasSaveGame && onResumeGame && (
          <button
            onClick={() => {
              sound.playClick();
              onResumeGame();
            }}
            className="w-full py-3.5 px-6 rounded-md bg-gradient-to-r from-amber-700 via-amber-600 to-amber-700 hover:from-amber-600 hover:to-amber-600 text-stone-100 font-bold tracking-wide border border-amber-400/40 shadow-lg shadow-amber-950/50 active:scale-98 transition-all min-h-[48px] cursor-pointer"
          >
            {STRINGS.loadGame}
          </button>
        )}

        <button
          data-start="true"
          onClick={() => {
            sound.playClick();
            onStartNewGame();
          }}
          className="w-full py-3.5 px-6 rounded-md bg-stone-900/90 hover:bg-stone-800 text-amber-200 font-bold tracking-wide border border-amber-600/30 hover:border-amber-500 shadow-md shadow-stone-950/60 active:scale-98 transition-all min-h-[48px] cursor-pointer flex items-center justify-center gap-2"
        >
          <span>{STRINGS.startNewGame}</span>
        </button>

        <div className="grid grid-cols-2 gap-2.5">
          <button
            onClick={() => {
              sound.playClick();
              onOpenTutorial();
            }}
            className="py-2.5 px-3 rounded bg-stone-900/80 hover:bg-stone-800 text-stone-300 text-xs font-semibold border border-stone-700/60 transition-colors min-h-[44px] cursor-pointer"
          >
            Tutorial Primer
          </button>
          <button
            onClick={() => {
              sound.playClick();
              onOpenSettings();
            }}
            className="py-2.5 px-3 rounded bg-stone-900/80 hover:bg-stone-800 text-stone-300 text-xs font-semibold border border-stone-700/60 transition-colors min-h-[44px] cursor-pointer"
          >
            {STRINGS.settings}
          </button>
        </div>
      </div>

      {/* Control Hint Line */}
      <div className="relative z-10 text-center pb-2 text-[11px] text-stone-500 tracking-wider">
        <span>{STRINGS.controlHint}</span>
      </div>
    </div>
  );
};
