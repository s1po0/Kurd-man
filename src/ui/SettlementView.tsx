/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { HeroData, PartyData, SettlementData } from '../core/types';
import { COMPANIONS } from '../data/companions';
import { FACTIONS } from '../data/factions';
import { ITEMS } from '../data/items';
import { STRINGS } from '../data/strings';
import { TROOPS } from '../data/troops';
import { sound } from '../audio/audioEngine';
import { getPartyCargoWeight, getPartyMaxTroops, getPartyMaxWeight, getPartySize } from '../campaign/simulation';

interface SettlementViewProps {
  settlement: SettlementData;
  hero: HeroData;
  playerParty: PartyData;
  onDepart: () => void;
  onRest: () => void;
  onRecruitTroop: (troopId: string, count: number, cost: number) => void;
  onHireCompanion: (companionId: string) => void;
  onBuyItem: (itemId: string, price: number) => void;
  onSellItem: (itemId: string, price: number) => void;
}

type SubScreen = 'MAIN' | 'MARKET' | 'TAVERN' | 'RECRUIT' | 'ARENA';

export const SettlementView: React.FC<SettlementViewProps> = ({
  settlement,
  hero,
  playerParty,
  onDepart,
  onRest,
  onRecruitTroop,
  onHireCompanion,
  onBuyItem,
  onSellItem,
}) => {
  const [subScreen, setSubScreen] = useState<SubScreen>('MAIN');
  const [marketFilter, setMarketFilter] = useState<'all' | 'food' | 'trade' | 'gear'>('all');
  const [restNotification, setRestNotification] = useState<string | null>(null);

  const faction = FACTIONS[settlement.factionId] || FACTIONS.vaeloria;
  const partySize = getPartySize(playerParty);
  const maxTroops = getPartyMaxTroops(hero);
  const cargoWeight = getPartyCargoWeight(hero);
  const maxWeight = getPartyMaxWeight(hero, playerParty);

  const availableCompanions = COMPANIONS.filter((c) => c.culture === settlement.factionId);

  const handleRest = () => {
    sound.playMarchStep();
    onRest();
    setRestNotification(STRINGS.settlement.restComplete);
    setTimeout(() => setRestNotification(null), 3000);
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-stone-950 text-stone-100 p-3 md:p-6 select-none overflow-y-auto">
      {/* Header Hub Card */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-800 pb-3 mb-4">
        <div className="flex items-center gap-3">
          <div
            className="w-10 h-12 rounded border flex items-center justify-center font-bold text-lg"
            style={{
              backgroundColor: faction.primaryColor,
              borderColor: faction.secondaryColor,
              color: faction.secondaryColor,
            }}
          >
            {settlement.type === 'town' ? '🏰' : settlement.type === 'castle' ? '🛡️' : '🌾'}
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl md:text-2xl font-bold font-display text-amber-200">
                {settlement.name}
              </h2>
              <span className="text-xs px-2 py-0.5 rounded bg-stone-900 border border-stone-700 font-semibold text-stone-300 capitalize">
                {settlement.type}
              </span>
            </div>
            <p className="text-xs text-stone-400">
              Realm of the <span style={{ color: faction.secondaryColor }}>{faction.name}</span> · Prosperity: {settlement.prosperity}%
            </p>
          </div>
        </div>

        {/* Resources fast ticker */}
        <div className="flex items-center gap-4 text-xs font-mono">
          <div className="flex items-center gap-1 text-amber-300">
            <span>🪙</span>
            <span className="font-bold">{hero.gold} Gold</span>
          </div>
          <div className="flex items-center gap-1 text-stone-300">
            <span>🛡️</span>
            <span>
              {partySize}/{maxTroops} Warband
            </span>
          </div>
          <button
            onClick={() => {
              sound.playClick();
              onDepart();
            }}
            className="py-2 px-4 rounded bg-stone-800 hover:bg-stone-700 text-stone-200 border border-stone-600 text-xs font-bold min-h-[44px] cursor-pointer"
          >
            {STRINGS.settlement.leave}
          </button>
        </div>
      </div>

      {/* Rest notification banner */}
      {restNotification && (
        <div className="bg-emerald-950/80 border border-emerald-500/60 text-emerald-200 text-xs p-2.5 rounded mb-3 text-center">
          {restNotification}
        </div>
      )}

      {/* === SUB-SCREEN: MAIN HUB === */}
      {subScreen === 'MAIN' && (
        <div className="flex-1 flex flex-col justify-center max-w-2xl mx-auto w-full space-y-3">
          <div className="p-4 rounded-lg bg-stone-900/80 border border-stone-800">
            <p className="text-xs md:text-sm text-stone-300 font-serif italic mb-4">
              {settlement.type === 'town'
                ? STRINGS.settlement.townGreeting.replace('{name}', settlement.name)
                : settlement.type === 'village'
                ? STRINGS.settlement.villageGreeting.replace('{name}', settlement.name)
                : STRINGS.settlement.castleGreeting.replace('{name}', settlement.name)}
            </p>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              <button
                onClick={() => {
                  sound.playClick();
                  setSubScreen('MARKET');
                }}
                className="p-3.5 rounded-lg bg-stone-800/80 hover:bg-stone-750 border border-stone-700 text-left min-h-[56px] transition-all cursor-pointer flex items-center justify-between"
              >
                <div>
                  <span className="block font-bold text-sm text-amber-200">
                    {STRINGS.settlement.visitMarket}
                  </span>
                  <span className="text-[11px] text-stone-400">Trade goods, grain, weapons, armor</span>
                </div>
                <span className="text-xl">⚖️</span>
              </button>

              <button
                onClick={() => {
                  sound.playClick();
                  setSubScreen('RECRUIT');
                }}
                className="p-3.5 rounded-lg bg-stone-800/80 hover:bg-stone-750 border border-stone-700 text-left min-h-[56px] transition-all cursor-pointer flex items-center justify-between"
              >
                <div>
                  <span className="block font-bold text-sm text-amber-200">
                    {STRINGS.settlement.recruitTroops}
                  </span>
                  <span className="text-[11px] text-stone-400">
                    {settlement.recruitsAvailable.reduce((s, r) => s + r.count, 0)} volunteers waiting
                  </span>
                </div>
                <span className="text-xl">🗡️</span>
              </button>

              {settlement.type === 'town' && (
                <button
                  onClick={() => {
                    sound.playClick();
                    setSubScreen('TAVERN');
                  }}
                  className="p-3.5 rounded-lg bg-stone-800/80 hover:bg-stone-750 border border-stone-700 text-left min-h-[56px] transition-all cursor-pointer flex items-center justify-between"
                >
                  <div>
                    <span className="block font-bold text-sm text-amber-200">
                      {STRINGS.settlement.visitTavern}
                    </span>
                    <span className="text-[11px] text-stone-400">Companions, rumors & sellswords</span>
                  </div>
                  <span className="text-xl">🍺</span>
                </button>
              )}

              {settlement.type === 'town' && (
                <button
                  onClick={() => {
                    sound.playClick();
                    setSubScreen('ARENA');
                  }}
                  className="p-3.5 rounded-lg bg-stone-800/80 hover:bg-stone-750 border border-stone-700 text-left min-h-[56px] transition-all cursor-pointer flex items-center justify-between"
                >
                  <div>
                    <span className="block font-bold text-sm text-amber-200">
                      {STRINGS.settlement.visitArena}
                    </span>
                    <span className="text-[11px] text-stone-400">Prize fights & tournaments</span>
                  </div>
                  <span className="text-xl">🏟️</span>
                </button>
              )}

              <button
                onClick={handleRest}
                className="p-3.5 rounded-lg bg-stone-800/80 hover:bg-stone-750 border border-stone-700 text-left min-h-[56px] transition-all cursor-pointer flex items-center justify-between sm:col-span-2"
              >
                <div>
                  <span className="block font-bold text-sm text-amber-200">
                    {STRINGS.settlement.restWait}
                  </span>
                  <span className="text-[11px] text-stone-400">
                    Heal wounded warriors and recover stamina safely
                  </span>
                </div>
                <span className="text-xl">⛺</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* === SUB-SCREEN: MARKETPLACE === */}
      {subScreen === 'MARKET' && (
        <div className="flex-1 flex flex-col space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <button
                onClick={() => setSubScreen('MAIN')}
                className="py-1 px-3 rounded bg-stone-800 border border-stone-700 text-xs min-h-[36px]"
              >
                ← Back
              </button>
              <h3 className="font-bold text-base text-amber-300 font-display">
                {STRINGS.market.title}
              </h3>
            </div>

            {/* Filter Tabs */}
            <div className="flex items-center gap-1 bg-stone-900 rounded p-1 border border-stone-800 text-xs">
              {(['all', 'food', 'trade', 'gear'] as const).map((cat) => (
                <button
                  key={cat}
                  onClick={() => {
                    sound.playClick();
                    setMarketFilter(cat);
                  }}
                  className={`px-2.5 py-1 rounded font-semibold capitalize min-h-[32px] ${
                    marketFilter === cat
                      ? 'bg-amber-700 text-amber-100'
                      : 'text-stone-400 hover:text-stone-200'
                  }`}
                >
                  {cat}
                </button>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 flex-1 overflow-y-auto">
            {/* Merchant Stock */}
            <div className="bg-stone-900/80 border border-stone-800 rounded-lg p-3 flex flex-col">
              <span className="text-xs font-bold text-amber-400 mb-2">Settlement Stalls (Buy)</span>
              <div className="space-y-2 overflow-y-auto max-h-96 pr-1">
                {Object.entries(settlement.marketGoods)
                  .filter(([itemId]) => {
                    const def = ITEMS[itemId];
                    if (!def) return false;
                    if (marketFilter === 'food') return def.type === 'food';
                    if (marketFilter === 'trade') return def.type === 'tradeGood';
                    if (marketFilter === 'gear') return def.type !== 'food' && def.type !== 'tradeGood';
                    return true;
                  })
                  .map(([itemId, marketInfo]) => {
                    const def = ITEMS[itemId];
                    if (!def) return null;
                    const canAfford = hero.gold >= marketInfo.buyPrice;

                    return (
                      <div
                        key={itemId}
                        className="flex items-center justify-between p-2 rounded bg-stone-950/60 border border-stone-800 text-xs"
                      >
                        <div className="flex-1 pr-2">
                          <span className="font-bold text-stone-200">{def.name}</span>
                          <p className="text-[10px] text-stone-400 line-clamp-1">{def.description}</p>
                          <span className="text-[10px] text-stone-400">
                            Available: {marketInfo.quantity} · Wt: {def.weight}kg
                          </span>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="font-mono font-bold text-amber-300">
                            {marketInfo.buyPrice} D
                          </span>
                          <button
                            onClick={() => {
                              onBuyItem(itemId, marketInfo.buyPrice);
                            }}
                            disabled={!canAfford || marketInfo.quantity <= 0}
                            className="py-1 px-3 rounded bg-amber-700 hover:bg-amber-600 disabled:opacity-30 text-amber-100 font-bold text-xs min-h-[36px] cursor-pointer"
                          >
                            Buy
                          </button>
                        </div>
                      </div>
                    );
                  })}
              </div>
            </div>

            {/* Player Inventory (Sell) */}
            <div className="bg-stone-900/80 border border-stone-800 rounded-lg p-3 flex flex-col">
              <div className="flex justify-between items-center mb-2">
                <span className="text-xs font-bold text-amber-400">Your Luggage (Sell)</span>
                <span className="text-[11px] text-stone-400 font-mono">
                  Burden: {cargoWeight.toFixed(1)} / {maxWeight} kg
                </span>
              </div>
              <div className="space-y-2 overflow-y-auto max-h-96 pr-1">
                {hero.inventory.length === 0 ? (
                  <p className="text-xs text-stone-500 italic p-4 text-center">
                    Your luggage wagons are currently empty.
                  </p>
                ) : (
                  hero.inventory.map((invItem) => {
                    const def = ITEMS[invItem.id];
                    if (!def) return null;
                    const marketPrice = settlement.marketGoods[invItem.id]?.sellPrice || Math.round(def.value * 0.7);

                    return (
                      <div
                        key={invItem.id}
                        className="flex items-center justify-between p-2 rounded bg-stone-950/60 border border-stone-800 text-xs"
                      >
                        <div className="flex-1 pr-2">
                          <span className="font-bold text-stone-200">
                            {def.name} (x{invItem.count})
                          </span>
                          <span className="block text-[10px] text-stone-400">
                            Weight: {(def.weight * invItem.count).toFixed(1)}kg
                          </span>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="font-mono font-bold text-emerald-400">
                            +{marketPrice} D
                          </span>
                          <button
                            onClick={() => {
                              onSellItem(invItem.id, marketPrice);
                            }}
                            className="py-1 px-3 rounded bg-stone-800 hover:bg-stone-700 border border-stone-600 text-stone-100 font-bold text-xs min-h-[36px] cursor-pointer"
                          >
                            Sell
                          </button>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* === SUB-SCREEN: RECRUIT VOLUNTEERS === */}
      {subScreen === 'RECRUIT' && (
        <div className="flex-1 flex flex-col space-y-3 max-w-xl mx-auto w-full">
          <div className="flex items-center gap-2">
            <button
              onClick={() => setSubScreen('MAIN')}
              className="py-1 px-3 rounded bg-stone-800 border border-stone-700 text-xs min-h-[36px]"
            >
              ← Back
            </button>
            <h3 className="font-bold text-base text-amber-300 font-display">
              {STRINGS.settlement.recruitTroops}
            </h3>
          </div>

          <div className="bg-stone-900/80 border border-stone-800 rounded-lg p-4 space-y-3">
            {settlement.recruitsAvailable.length === 0 ||
            settlement.recruitsAvailable.every((r) => r.count <= 0) ? (
              <p className="text-xs text-stone-400 italic">
                {STRINGS.settlement.noRecruits}
              </p>
            ) : (
              settlement.recruitsAvailable.map((recruit) => {
                const def = TROOPS[recruit.troopId];
                if (!def || recruit.count <= 0) return null;
                const recruitCost = def.wage * 5; // e.g. 10 dinars per recruit
                const canRecruitOne = hero.gold >= recruitCost && partySize < maxTroops;

                return (
                  <div
                    key={recruit.troopId}
                    className="flex flex-col sm:flex-row sm:items-center justify-between p-3 rounded-lg bg-stone-950/70 border border-stone-800 gap-3"
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-sm text-amber-200">{def.name}</span>
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-stone-900 border border-stone-700 text-stone-400 uppercase">
                          Tier {def.tier} · {def.role}
                        </span>
                      </div>
                      <p className="text-xs text-stone-300 mt-1">{def.equipmentDesc}</p>
                      <p className="text-[11px] text-stone-400 mt-0.5">
                        Available: {recruit.count} volunteers · Cost: {recruitCost} Gold each
                      </p>
                    </div>

                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => {
                          onRecruitTroop(recruit.troopId, 1, recruitCost);
                        }}
                        disabled={!canRecruitOne}
                        className="py-2 px-3 rounded bg-amber-700 hover:bg-amber-600 disabled:opacity-30 text-amber-100 font-bold text-xs min-h-[44px] cursor-pointer"
                      >
                        Recruit 1
                      </button>
                      <button
                        onClick={() => {
                          const maxAfford = Math.floor(hero.gold / recruitCost);
                          const maxRoom = maxTroops - partySize;
                          const toRecruit = Math.min(recruit.count, maxAfford, maxRoom);
                          if (toRecruit > 0) {
                            onRecruitTroop(recruit.troopId, toRecruit, recruitCost * toRecruit);
                          }
                        }}
                        disabled={!canRecruitOne}
                        className="py-2 px-3 rounded bg-amber-800 hover:bg-amber-700 disabled:opacity-30 text-amber-100 font-bold text-xs min-h-[44px] cursor-pointer"
                      >
                        Recruit All
                      </button>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
      )}

      {/* === SUB-SCREEN: TAVERN === */}
      {subScreen === 'TAVERN' && (
        <div className="flex-1 flex flex-col space-y-3 max-w-xl mx-auto w-full">
          <div className="flex items-center gap-2">
            <button
              onClick={() => setSubScreen('MAIN')}
              className="py-1 px-3 rounded bg-stone-800 border border-stone-700 text-xs min-h-[36px]"
            >
              ← Back
            </button>
            <h3 className="font-bold text-base text-amber-300 font-display">
              {STRINGS.settlement.visitTavern}
            </h3>
          </div>

          <div className="bg-stone-900/80 border border-stone-800 rounded-lg p-4 space-y-3">
            <p className="text-xs text-stone-400 italic mb-2">
              Lute melodies drift above clinking tankards of spiced ale. Several wandering sellswords and companions sit by the hearth.
            </p>

            {availableCompanions.map((comp) => {
              const canAfford = hero.gold >= comp.hireCost;

              return (
                <div
                  key={comp.id}
                  className="p-3.5 rounded-lg bg-stone-950/70 border border-stone-800 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
                >
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-sm text-amber-200">{comp.name}</span>
                      <span className="text-[10px] text-amber-400 font-medium">({comp.title})</span>
                    </div>
                    <p className="text-xs text-stone-300 mt-1">{comp.backstory}</p>
                    <p className="text-[11px] text-stone-400 mt-1">
                      Wage: {comp.dailyWage} Gold / day · Hire Fee: {comp.hireCost} Gold
                    </p>
                  </div>

                  <button
                    onClick={() => {
                      onHireCompanion(comp.id);
                    }}
                    disabled={!canAfford}
                    className="py-2.5 px-4 rounded bg-amber-700 hover:bg-amber-600 disabled:opacity-30 text-amber-100 font-bold text-xs min-h-[44px] cursor-pointer whitespace-nowrap"
                  >
                    Hire Companion
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* === SUB-SCREEN: ARENA === */}
      {subScreen === 'ARENA' && (
        <div className="flex-1 flex flex-col space-y-3 max-w-xl mx-auto w-full">
          <div className="flex items-center gap-2">
            <button
              onClick={() => setSubScreen('MAIN')}
              className="py-1 px-3 rounded bg-stone-800 border border-stone-700 text-xs min-h-[36px]"
            >
              ← Back
            </button>
            <h3 className="font-bold text-base text-amber-300 font-display">
              {STRINGS.settlement.visitArena}
            </h3>
          </div>

          <div className="bg-stone-900/80 border border-stone-800 rounded-lg p-5 text-center space-y-3">
            <span className="text-4xl block">🏟️</span>
            <h4 className="text-lg font-bold text-amber-200 font-display">
              Grand Martial Tournaments of Caladria
            </h4>
            <p className="text-xs text-stone-300 max-w-md mx-auto">
              Knights and renowned gladiators compete in round-robin clashes for purses of gold and sovereign renown. Full real-time player arena combat unlocks in Phase 2.
            </p>
            <button
              onClick={() => {
                sound.playClash();
                setRestNotification('You observed the arena matches and gained +5 Renown!');
                hero.renown += 5;
                setSubScreen('MAIN');
              }}
              className="py-2.5 px-6 rounded bg-amber-700 hover:bg-amber-600 text-amber-100 font-bold text-xs min-h-[44px] cursor-pointer"
            >
              Spectate Match (+5 Renown)
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
