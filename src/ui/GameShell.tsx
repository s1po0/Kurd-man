/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Camera, CampaignMapRenderer } from '../campaign/mapRenderer';
import { findPath } from '../campaign/pathfinding';
import {
  CampaignSimulationState,
  createInitialAIParties,
  getPartyCargoWeight,
  getPartyMaxTroops,
  getPartyMaxWeight,
  getPartySize,
  tickCampaignSimulation,
} from '../campaign/simulation';
import { generateCampaignMap } from '../campaign/terrain';
import { resolveSkirmish, SkirmishResult } from '../battle/skirmishEngine';
import { MAP_CONFIG } from '../core/constants';
import { SeededRNG } from '../core/rng';
import {
  AttributeKey,
  CampaignTime,
  EquipmentSlots,
  GameSettings,
  GameView,
  HeroData,
  PartyData,
  SaveGameState,
  SettlementData,
  SkillKey,
  TileCoord,
} from '../core/types';
import { SaveManager } from '../save/saveManager';
import { sound } from '../audio/audioEngine';
import { TitleScreen } from './TitleScreen';
import { CharacterCreation } from './CharacterCreation';
import { HUD } from './HUD';
import { SettlementView } from './SettlementView';
import { PartyScreen } from './PartyScreen';
import { InventoryScreen } from './InventoryScreen';
import { CharacterSheet } from './CharacterSheet';
import { EncounterModal } from './EncounterModal';
import { BattleReportModal } from './BattleReportModal';
import { SaveLoadModal } from './SaveLoadModal';
import { SettingsModal } from './SettingsModal';
import { TutorialModal } from './TutorialModal';

export const GameShell: React.FC = () => {
  const [currentView, setCurrentView] = useState<GameView>('TITLE');
  const [activeSubModal, setActiveSubModal] = useState<GameView | null>(null);

  // Settings State
  const [settings, setSettings] = useState<GameSettings>({
    soundMuted: false,
    masterVolume: 0.5,
    musicVolume: 0.4,
    sfxVolume: 0.6,
    dprCap: 2,
    showFps: false,
    fogOfWar: true,
    difficulty: 'normal',
  });

  // Hero & Simulation State
  const [hero, setHero] = useState<HeroData | null>(null);
  const [hasSaveGame, setHasSaveGame] = useState<boolean>(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  // Skirmish Battle Result
  const [activeBattleReport, setActiveBattleReport] = useState<SkirmishResult | null>(null);

  // Canvas Refs
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const rendererRef = useRef<CampaignMapRenderer | null>(null);

  // Core Simulation State Container (Ref for 60fps loop stability)
  const simStateRef = useRef<CampaignSimulationState | null>(null);

  // Camera State
  const cameraRef = useRef<Camera>({
    x: 44,
    y: 28,
    zoom: MAP_CONFIG.defaultZoom,
  });

  // Touch & Drag state
  const isDraggingRef = useRef<boolean>(false);
  const dragStartRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });
  const cameraStartRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });
  const touchStartTimeRef = useRef<number>(0);
  const destinationTapRef = useRef<TileCoord | null>(null);

  // Check existing saves on mount
  useEffect(() => {
    SaveManager.listSaves().then((list) => {
      const hasAny = Object.values(list).some((s) => s !== null);
      setHasSaveGame(hasAny);
    });

    // Check ?autostart=1 query param
    if (typeof window !== 'undefined') {
      const params = new URLSearchParams(window.location.search);
      if (params.get('autostart') === '1') {
        initDefaultHeroAndStart();
      }
    }
  }, []);

  const showToast = useCallback((msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3500);
  }, []);

  // Initialize fresh campaign world
  const initCampaign = useCallback((heroData: HeroData) => {
    const seed = 4281;
    const rng = new SeededRNG(seed);
    const map = generateCampaignMap(seed);

    // Starting settlement in player's culture
    const startTown =
      map.settlements.find((s) => s.type === 'town' && s.factionId === heroData.culture) ||
      map.settlements[0];

    const playerParty: PartyData = {
      id: 'party_player',
      name: `${heroData.name}'s Host`,
      partyType: 'player',
      factionId: heroData.culture,
      leaderName: heroData.name,
      x: startTown.x + 1,
      y: startTown.y + 1,
      path: [],
      troops: [
        {
          troopId: `${heroData.culture === 'vaeloria' ? 'vael' : heroData.culture === 'ironmark' ? 'iron' : heroData.culture === 'dunmere' ? 'dun' : heroData.culture === 'sunreach' ? 'sun' : 'frost'}_recruit`,
          count: 8,
          woundedCount: 0,
          xp: 0,
        },
      ],
      prisoners: [],
      gold: heroData.gold,
      morale: 75,
      foodDays: 14,
      speed: 4.8,
      behavior: 'idle',
      lastDecisionTime: 0,
    };

    const aiParties = createInitialAIParties(map, rng);

    const exploredTiles: boolean[][] = [];
    for (let y = 0; y < map.height; y++) {
      const row: boolean[] = [];
      for (let x = 0; x < map.width; x++) {
        row.push(false);
      }
      exploredTiles.push(row);
    }

    const time: CampaignTime = {
      day: 1,
      month: 1,
      year: 1084,
      hour: 8,
      minute: 0,
      timeSpeed: 1,
    };

    simStateRef.current = {
      map,
      hero: heroData,
      playerParty,
      aiParties,
      time,
      exploredTiles,
      logMessages: [],
    };

    cameraRef.current.x = playerParty.x;
    cameraRef.current.y = playerParty.y;

    setHero({ ...heroData });
    setCurrentView('CAMPAIGN');
    document.body.dataset.state = 'playing';
    showToast(`Welcome to Caladria, Commander ${heroData.name}! Tap map to march.`);
  }, [showToast]);

  const initDefaultHeroAndStart = useCallback(() => {
    const defaultHero: HeroData = {
      id: 'hero_player',
      name: 'Roland of the Marches',
      culture: 'vaeloria',
      backgroundOrigin: 'Impoverished Landed Noble',
      gender: 'male',
      hp: 100,
      maxHp: 100,
      gold: 750,
      renown: 25,
      honor: 15,
      attributes: { vigor: 4, control: 3, endurance: 4, cunning: 3, social: 4, intelligence: 3 },
      skills: {
        oneHanded: 35,
        twoHanded: 20,
        polearm: 30,
        bow: 20,
        throwing: 15,
        riding: 45,
        athletics: 30,
        tactics: 35,
        scouting: 25,
        leadership: 35,
        trade: 25,
        steward: 30,
        medicine: 25,
      },
      attributePoints: 1,
      skillPoints: 2,
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
        { id: 'food_grain', count: 5 },
        { id: 'food_bread', count: 3 },
        { id: 'trade_wool', count: 2 },
      ],
      bannerHeraldry: {
        symbolIndex: 0,
        bgPatternIndex: 0,
        primaryColor: '#1e3a8a',
        secondaryColor: '#f59e0b',
      },
    };

    initCampaign(defaultHero);
  }, [initCampaign]);

  // Handle Load from Save Game
  const handleLoadState = useCallback(async (slot: number): Promise<boolean> => {
    const save = await SaveManager.load(slot);
    if (!save) return false;

    const map = generateCampaignMap(save.mapSeed);
    map.settlements = save.settlements;

    simStateRef.current = {
      map,
      hero: save.hero,
      playerParty: save.playerParty,
      aiParties: save.aiParties,
      time: save.time,
      exploredTiles: save.exploredTiles,
      logMessages: [],
    };

    setHero({ ...save.hero });
    setSettings({ ...save.settings });
    cameraRef.current.x = save.playerParty.x;
    cameraRef.current.y = save.playerParty.y;

    setCurrentView('CAMPAIGN');
    setActiveSubModal(null);
    document.body.dataset.state = 'playing';
    showToast(`Resumed campaign from Day ${save.time.day}!`);
    return true;
  }, [showToast]);

  // Handle Save Game
  const handleSaveState = useCallback(async (slot: number): Promise<boolean> => {
    const sim = simStateRef.current;
    if (!sim || !hero) return false;

    const save: SaveGameState = {
      version: 1,
      metadata: {
        slot,
        saveName: `Day ${sim.time.day} - ${hero.name}`,
        timestamp: Date.now(),
        playtimeSeconds: 0,
        heroName: hero.name,
        heroCulture: hero.culture,
        day: sim.time.day,
        gold: hero.gold,
        partySize: getPartySize(sim.playerParty),
        version: 1,
      },
      hero,
      playerParty: sim.playerParty,
      aiParties: sim.aiParties,
      settlements: sim.map.settlements,
      time: sim.time,
      mapSeed: sim.map.seed,
      exploredTiles: sim.exploredTiles,
      questFlags: {},
      settings,
    };

    const success = await SaveManager.save(slot, save);
    if (success) {
      setHasSaveGame(true);
      showToast('Campaign chronicle successfully etched into memory!');
    }
    return success;
  }, [hero, settings, showToast]);

  // 60 FPS Fixed-Timestep Simulation & Render Loop
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    if (!rendererRef.current) {
      rendererRef.current = new CampaignMapRenderer(canvas);
    }
    const renderer = rendererRef.current;

    const handleResize = () => {
      renderer.resize(window.innerWidth, window.innerHeight);
    };
    handleResize();
    window.addEventListener('resize', handleResize);

    let lastTime = performance.now();
    let animId: number;

    const gameLoop = (currentTime: number) => {
      const rawDt = (currentTime - lastTime) / 1000;
      lastTime = currentTime;
      const dt = Math.max(0, Math.min(rawDt, 0.05));

      const sim = simStateRef.current;
      if (sim && currentView === 'CAMPAIGN' && !activeSubModal) {
        // Step Simulation
        tickCampaignSimulation(sim, dt);

        // Smooth camera track player party
        const cam = cameraRef.current;
        if (!isDraggingRef.current) {
          cam.x += (sim.playerParty.x - cam.x) * 0.08;
          cam.y += (sim.playerParty.y - cam.y) * 0.08;
        }

        // Check if settlement entered
        if (sim.activeSettlement && !activeSubModal) {
          setActiveSubModal('SETTLEMENT');
        }

        // Check if encounter triggered
        if (sim.activeEncounterParty && !activeSubModal) {
          sound.playHorn();
          setActiveSubModal('ENCOUNTER');
        }

        // Render Frame
        renderer.render(
          sim.map,
          sim.playerParty,
          sim.aiParties,
          cam,
          sim.time,
          sim.exploredTiles,
          settings.fogOfWar,
          destinationTapRef.current
        );
      }

      animId = requestAnimationFrame(gameLoop);
    };

    animId = requestAnimationFrame(gameLoop);

    return () => {
      cancelAnimationFrame(animId);
      window.removeEventListener('resize', handleResize);
    };
  }, [currentView, activeSubModal, settings.fogOfWar]);

  // Canvas Touch & Pointer Handlers (Tap-to-move, Pan, Zoom)
  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    isDraggingRef.current = false;
    touchStartTimeRef.current = performance.now();
    dragStartRef.current = { x: e.clientX, y: e.clientY };
    cameraStartRef.current = { x: cameraRef.current.x, y: cameraRef.current.y };
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const dx = e.clientX - dragStartRef.current.x;
    const dy = e.clientY - dragStartRef.current.y;
    const dist = Math.hypot(dx, dy);

    if (dist > 8) {
      isDraggingRef.current = true;
      const effectiveTilePx = MAP_CONFIG.tileSize * cameraRef.current.zoom;
      cameraRef.current.x = cameraStartRef.current.x - dx / effectiveTilePx;
      cameraRef.current.y = cameraStartRef.current.y - dy / effectiveTilePx;
    }
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const pressDuration = performance.now() - touchStartTimeRef.current;

    // If it was a short tap without drag -> Issue Tap-to-Move command!
    if (!isDraggingRef.current && pressDuration < 350) {
      const sim = simStateRef.current;
      if (!sim) return;

      const canvas = canvasRef.current;
      if (!canvas) return;

      const rect = canvas.getBoundingClientRect();
      const screenX = e.clientX - rect.left;
      const screenY = e.clientY - rect.top;

      const screenCenterX = rect.width / 2;
      const screenCenterY = rect.height / 2;
      const effectiveTilePx = MAP_CONFIG.tileSize * cameraRef.current.zoom;

      // Convert Screen coords to World Tile coords
      const tileX = Math.floor(cameraRef.current.x + (screenX - screenCenterX) / effectiveTilePx);
      const tileY = Math.floor(cameraRef.current.y + (screenY - screenCenterY) / effectiveTilePx);

      if (tileX >= 0 && tileX < sim.map.width && tileY >= 0 && tileY < sim.map.height) {
        sound.playMarchStep();
        destinationTapRef.current = { x: tileX, y: tileY };

        // Calculate path via A*
        const path = findPath(sim.playerParty.x, sim.playerParty.y, tileX, tileY, sim.map);
        if (path.length > 0) {
          sim.playerParty.path = path;
          // Auto unpause when marching
          if (sim.time.timeSpeed === 0) {
            sim.time.timeSpeed = 1;
          }
        }
      }
    }

    isDraggingRef.current = false;
  };

  // Wheel zoom
  const handleWheel = (e: React.WheelEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const delta = e.deltaY < 0 ? 0.15 : -0.15;
    cameraRef.current.zoom = Math.max(
      MAP_CONFIG.minZoom,
      Math.min(MAP_CONFIG.maxZoom, cameraRef.current.zoom + delta)
    );
  };

  // Combat Attack Action from Encounter Modal
  const handleEncounterAttack = () => {
    const sim = simStateRef.current;
    if (!sim || !sim.activeEncounterParty || !hero) return;

    const terrain = sim.map.tiles[Math.floor(sim.playerParty.y)]?.[Math.floor(sim.playerParty.x)] || 'plains';
    const result = resolveSkirmish(
      sim.playerParty,
      sim.activeEncounterParty,
      terrain,
      hero.skills.tactics,
      hero.skills.medicine
    );

    if (result.winner === 'player') {
      sound.playVictoryFanfare();
      hero.gold += result.goldLooted;
      hero.renown += result.renownEarned;

      // Add looted items
      for (const itemId of result.itemsLooted) {
        const existing = hero.inventory.find((i) => i.id === itemId);
        if (existing) existing.count++;
        else hero.inventory.push({ id: itemId, count: 1 });
      }

      // Add prisoners
      for (const p of result.prisonersCaptured) {
        sim.playerParty.prisoners.push(p);
      }

      // Remove defeated AI party from world
      sim.aiParties = sim.aiParties.filter((p) => p.id !== sim.activeEncounterParty?.id);
    } else {
      // Defeat: lose gold & morale
      hero.gold = Math.max(0, Math.floor(hero.gold * 0.4));
      sim.playerParty.morale = Math.max(10, sim.playerParty.morale - 30);
    }

    setActiveBattleReport(result);
    sim.activeEncounterParty = undefined;
    setActiveSubModal(null);
  };

  // Recruitment Handler
  const handleRecruitTroop = (troopId: string, count: number, cost: number) => {
    const sim = simStateRef.current;
    if (!sim || !hero) return;

    if (hero.gold >= cost) {
      sound.playCoin();
      hero.gold -= cost;

      const existing = sim.playerParty.troops.find((t) => t.troopId === troopId);
      if (existing) {
        existing.count += count;
      } else {
        sim.playerParty.troops.push({
          troopId,
          count,
          woundedCount: 0,
          xp: 0,
        });
      }

      // Decrement available volunteers in settlement
      if (sim.activeSettlement) {
        const recruitStack = sim.activeSettlement.recruitsAvailable.find((r) => r.troopId === troopId);
        if (recruitStack) {
          recruitStack.count = Math.max(0, recruitStack.count - count);
        }
      }

      setHero({ ...hero });
      showToast(`Recruited ${count} warriors into your host!`);
    }
  };

  // Market Buy
  const handleMarketBuy = (itemId: string, price: number) => {
    const sim = simStateRef.current;
    if (!sim || !hero) return;

    if (hero.gold >= price) {
      sound.playCoin();
      hero.gold -= price;

      const existing = hero.inventory.find((i) => i.id === itemId);
      if (existing) {
        existing.count += 1;
      } else {
        hero.inventory.push({ id: itemId, count: 1 });
      }

      if (sim.activeSettlement?.marketGoods[itemId]) {
        sim.activeSettlement.marketGoods[itemId].quantity = Math.max(
          0,
          sim.activeSettlement.marketGoods[itemId].quantity - 1
        );
      }

      setHero({ ...hero });
    }
  };

  // Market Sell
  const handleMarketSell = (itemId: string, price: number) => {
    const sim = simStateRef.current;
    if (!sim || !hero) return;

    const existing = hero.inventory.find((i) => i.id === itemId);
    if (existing && existing.count > 0) {
      sound.playCoin();
      existing.count -= 1;
      hero.gold += price;

      hero.inventory = hero.inventory.filter((i) => i.count > 0);

      if (sim.activeSettlement?.marketGoods[itemId]) {
        sim.activeSettlement.marketGoods[itemId].quantity += 1;
      }

      setHero({ ...hero });
    }
  };

  // Troop Upgrade
  const handleUpgradeTroop = (troopId: string, upgradeToId: string, cost: number) => {
    const sim = simStateRef.current;
    if (!sim || !hero) return;

    if (hero.gold >= cost) {
      sound.playClash();
      hero.gold -= cost;

      const stack = sim.playerParty.troops.find((t) => t.troopId === troopId);
      if (stack && stack.count > 0) {
        stack.count -= 1;
        stack.xp = Math.max(0, stack.xp - 100);

        const upgraded = sim.playerParty.troops.find((t) => t.troopId === upgradeToId);
        if (upgraded) {
          upgraded.count += 1;
        } else {
          sim.playerParty.troops.push({
            troopId: upgradeToId,
            count: 1,
            woundedCount: 0,
            xp: 0,
          });
        }

        sim.playerParty.troops = sim.playerParty.troops.filter((t) => t.count > 0);
        setHero({ ...hero });
        showToast('Warrior successfully promoted to veteran rank!');
      }
    }
  };

  // Ransom Prisoners
  const handleRansomPrisoners = () => {
    const sim = simStateRef.current;
    if (!sim || !hero) return;

    let totalRansom = 0;
    for (const stack of sim.playerParty.prisoners) {
      totalRansom += stack.count * 25;
    }

    if (totalRansom > 0) {
      sound.playCoin();
      hero.gold += totalRansom;
      sim.playerParty.prisoners = [];
      setHero({ ...hero });
      showToast(`Ransomed all captives for +${totalRansom} Gold Dinars!`);
    }
  };

  // Equip Item
  const handleEquipItem = (itemId: string, slot: keyof EquipmentSlots) => {
    if (!hero) return;
    const prevItem = hero.equipment[slot];
    hero.equipment[slot] = itemId;

    const invIndex = hero.inventory.findIndex((i) => i.id === itemId);
    if (invIndex >= 0) {
      hero.inventory[invIndex].count -= 1;
      if (hero.inventory[invIndex].count <= 0) {
        hero.inventory.splice(invIndex, 1);
      }
    }

    if (prevItem) {
      const existing = hero.inventory.find((i) => i.id === prevItem);
      if (existing) existing.count++;
      else hero.inventory.push({ id: prevItem, count: 1 });
    }

    setHero({ ...hero });
    showToast('Equipment updated.');
  };

  // Unequip Item
  const handleUnequipItem = (slot: keyof EquipmentSlots) => {
    if (!hero) return;
    const current = hero.equipment[slot];
    if (current) {
      hero.equipment[slot] = undefined;
      const existing = hero.inventory.find((i) => i.id === current);
      if (existing) existing.count++;
      else hero.inventory.push({ id: current, count: 1 });
      setHero({ ...hero });
    }
  };

  // Rest Camp Handler
  const handleCampRest = () => {
    const sim = simStateRef.current;
    if (!sim) return;

    sound.playMarchStep();
    sim.time.hour = (sim.time.hour + 8) % 24;
    sim.time.day += sim.time.hour < 8 ? 1 : 0;

    // Heal 50% of wounded
    for (const stack of sim.playerParty.troops) {
      stack.woundedCount = Math.floor(stack.woundedCount * 0.5);
    }

    showToast('Your warband rested in camp. Wounded warriors have regained vitality.');
  };

  return (
    <div className="relative w-full h-full overflow-hidden bg-stone-950 select-none">
      {/* 2D Canvas for Campaign Map */}
      <canvas
        ref={canvasRef}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onWheel={handleWheel}
        className="absolute inset-0 w-full h-full cursor-grab active:cursor-grabbing touch-none"
      />

      {/* Screen Views State Routing */}
      {currentView === 'TITLE' && (
        <TitleScreen
          onStartNewGame={() => setCurrentView('CREATION')}
          onResumeGame={() => handleLoadState(0)}
          hasSaveGame={hasSaveGame}
          onOpenSettings={() => setActiveSubModal('SETTINGS')}
          onOpenTutorial={() => setActiveSubModal('TUTORIAL')}
        />
      )}

      {currentView === 'CREATION' && (
        <CharacterCreation
          onComplete={(newHero) => initCampaign(newHero)}
          onCancel={() => setCurrentView('TITLE')}
        />
      )}

      {currentView === 'CAMPAIGN' && hero && simStateRef.current && (
        <>
          {!activeSubModal && (
            <HUD
              hero={hero}
              playerParty={simStateRef.current.playerParty}
              time={simStateRef.current.time}
              activeView={'CAMPAIGN'}
              onSetTimeSpeed={(speed) => {
                if (simStateRef.current) simStateRef.current.time.timeSpeed = speed;
              }}
              onOpenView={(view) => setActiveSubModal(view === 'CAMPAIGN' ? null : view)}
              onCampRest={handleCampRest}
              logMessage={toastMessage}
            />
          )}

          {/* Sub-modals over active Campaign */}
          {activeSubModal === 'SETTLEMENT' && simStateRef.current.activeSettlement && (
            <SettlementView
              settlement={simStateRef.current.activeSettlement}
              hero={hero}
              playerParty={simStateRef.current.playerParty}
              onDepart={() => {
                if (simStateRef.current) simStateRef.current.activeSettlement = undefined;
                setActiveSubModal(null);
              }}
              onRest={handleCampRest}
              onRecruitTroop={handleRecruitTroop}
              onHireCompanion={(compId) => {
                sound.playCoin();
                showToast(`Wandering hero took the sworn oath to join your retinue!`);
              }}
              onBuyItem={handleMarketBuy}
              onSellItem={handleMarketSell}
            />
          )}

          {activeSubModal === 'PARTY_MENU' && (
            <PartyScreen
              hero={hero}
              playerParty={simStateRef.current.playerParty}
              onClose={() => setActiveSubModal(null)}
              onUpgradeTroop={handleUpgradeTroop}
              onDisbandTroop={(troopId) => {
                const s = simStateRef.current?.playerParty.troops.find((t) => t.troopId === troopId);
                if (s) s.count = Math.max(0, s.count - 1);
                setHero({ ...hero });
              }}
              onRansomPrisoners={handleRansomPrisoners}
              onRecruitPrisoner={(troopId) => {
                showToast('Captive swore allegiance to join your host.');
              }}
            />
          )}

          {activeSubModal === 'INVENTORY_MENU' && (
            <InventoryScreen
              hero={hero}
              playerParty={simStateRef.current.playerParty}
              onClose={() => setActiveSubModal(null)}
              onEquipItem={handleEquipItem}
              onUnequipItem={handleUnequipItem}
              onDropItem={(itemId) => {
                const existing = hero.inventory.find((i) => i.id === itemId);
                if (existing) {
                  existing.count--;
                  hero.inventory = hero.inventory.filter((i) => i.count > 0);
                  setHero({ ...hero });
                }
              }}
            />
          )}

          {activeSubModal === 'CHARACTER_SHEET' && (
            <CharacterSheet
              hero={hero}
              onClose={() => setActiveSubModal(null)}
              onAllocateAttribute={(attr) => {
                if (hero.attributePoints > 0) {
                  hero.attributePoints--;
                  hero.attributes[attr] = (hero.attributes[attr] || 3) + 1;
                  setHero({ ...hero });
                }
              }}
              onAllocateSkill={(skill) => {
                if (hero.skillPoints > 0) {
                  hero.skillPoints--;
                  hero.skills[skill] = (hero.skills[skill] || 15) + 10;
                  setHero({ ...hero });
                }
              }}
            />
          )}

          {activeSubModal === 'ENCOUNTER' && simStateRef.current.activeEncounterParty && (
            <EncounterModal
              playerParty={simStateRef.current.playerParty}
              enemyParty={simStateRef.current.activeEncounterParty}
              hero={hero}
              onAttack={handleEncounterAttack}
              onTalk={() => {}}
              onBribe={(cost) => {
                hero.gold = Math.max(0, hero.gold - cost);
                if (simStateRef.current) simStateRef.current.activeEncounterParty = undefined;
                setActiveSubModal(null);
                showToast('Safe-passage toll paid. You pass peacefully.');
              }}
              onFlee={() => {
                if (simStateRef.current) simStateRef.current.activeEncounterParty = undefined;
                setActiveSubModal(null);
                showToast('Your vanguard scattered into the brush and evaded battle.');
              }}
            />
          )}

          {activeSubModal === 'SAVELOAD' && (
            <SaveLoadModal
              onClose={() => setActiveSubModal(null)}
              onSaveToSlot={handleSaveState}
              onLoadFromSlot={handleLoadState}
            />
          )}
        </>
      )}

      {/* Global Modals (Settings & Tutorial) */}
      {activeSubModal === 'SETTINGS' && (
        <SettingsModal
          settings={settings}
          onUpdateSettings={(newSet) => setSettings({ ...settings, ...newSet })}
          onClose={() => setActiveSubModal(null)}
        />
      )}

      {activeSubModal === 'TUTORIAL' && (
        <TutorialModal onClose={() => setActiveSubModal(null)} />
      )}

      {/* Post-Skirmish Battle Report Modal */}
      {activeBattleReport && (
        <BattleReportModal
          result={activeBattleReport}
          onClose={() => setActiveBattleReport(null)}
        />
      )}
    </div>
  );
};
