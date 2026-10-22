/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { EquipmentSlots, HeroData, ItemDef, ItemType, PartyData } from '../core/types';
import { ITEMS } from '../data/items';
import { STRINGS } from '../data/strings';
import { sound } from '../audio/audioEngine';
import { getPartyCargoWeight, getPartyMaxWeight } from '../campaign/simulation';

interface InventoryScreenProps {
  hero: HeroData;
  playerParty: PartyData;
  onClose: () => void;
  onEquipItem: (itemId: string, slot: keyof EquipmentSlots) => void;
  onUnequipItem: (slot: keyof EquipmentSlots) => void;
  onDropItem: (itemId: string) => void;
}

export const InventoryScreen: React.FC<InventoryScreenProps> = ({
  hero,
  playerParty,
  onClose,
  onEquipItem,
  onUnequipItem,
  onDropItem,
}) => {
  const [selectedItem, setSelectedItem] = useState<ItemDef | null>(null);
  const [filterType, setFilterType] = useState<'all' | 'weapon' | 'armor' | 'food' | 'trade'>('all');

  const cargoWeight = getPartyCargoWeight(hero);
  const maxWeight = getPartyMaxWeight(hero, playerParty);

  const getSlotItem = (id?: string) => (id ? ITEMS[id] : null);

  const handleBackpackClick = (itemId: string) => {
    sound.playClick();
    const item = ITEMS[itemId];
    if (item) setSelectedItem(item);
  };

  const equipSelected = () => {
    if (!selectedItem) return;
    sound.playClick();

    let targetSlot: keyof EquipmentSlots = 'weapon1';
    if (selectedItem.type === 'head') targetSlot = 'head';
    else if (selectedItem.type === 'body') targetSlot = 'body';
    else if (selectedItem.type === 'hands') targetSlot = 'hands';
    else if (selectedItem.type === 'feet') targetSlot = 'feet';
    else if (selectedItem.type === 'horse') targetSlot = 'mount';
    else if (selectedItem.type === 'shield' || selectedItem.type === 'bow') targetSlot = 'shieldOrArrows';
    else targetSlot = 'weapon1';

    onEquipItem(selectedItem.id, targetSlot);
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-stone-950 text-stone-100 p-3 md:p-6 select-none overflow-y-auto">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-stone-800 pb-3 mb-4">
        <div>
          <h2 className="text-xl md:text-2xl font-bold font-display text-amber-200">
            {STRINGS.inventory.title}
          </h2>
          <div className="flex items-center gap-3 text-xs text-stone-400 mt-0.5">
            <span>
              {STRINGS.inventory.weightCapacity}:{' '}
              <strong
                className={`font-mono ${cargoWeight > maxWeight ? 'text-red-400 font-bold' : 'text-amber-200'}`}
              >
                {cargoWeight.toFixed(1)} / {maxWeight} kg
              </strong>
            </span>
            <span>·</span>
            <span>
              Purse:{' '}
              <strong className="text-amber-300 font-mono">{hero.gold} Gold</strong>
            </span>
          </div>
        </div>

        <button
          onClick={() => {
            sound.playClick();
            onClose();
          }}
          className="py-2 px-4 rounded bg-stone-800 hover:bg-stone-700 text-stone-200 border border-stone-700 text-xs font-bold min-h-[44px] cursor-pointer"
        >
          Close Inventory
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 flex-1">
        {/* Left: Paperdoll Equipment Slots (4 cols) */}
        <div className="lg:col-span-5 bg-stone-900/80 border border-stone-800 rounded-lg p-3.5 space-y-3">
          <span className="text-xs font-bold text-amber-400 uppercase tracking-wider block">
            Equipped Battle Gear
          </span>

          <div className="grid grid-cols-2 gap-2 text-xs">
            {/* Helm */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.head}
              item={getSlotItem(hero.equipment.head)}
              onUnequip={() => onUnequipItem('head')}
            />

            {/* Mount */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.mount}
              item={getSlotItem(hero.equipment.mount)}
              onUnequip={() => onUnequipItem('mount')}
            />

            {/* Armor Body */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.body}
              item={getSlotItem(hero.equipment.body)}
              onUnequip={() => onUnequipItem('body')}
            />

            {/* Shield / Quiver */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.shieldOrArrows}
              item={getSlotItem(hero.equipment.shieldOrArrows)}
              onUnequip={() => onUnequipItem('shieldOrArrows')}
            />

            {/* Gauntlets */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.hands}
              item={getSlotItem(hero.equipment.hands)}
              onUnequip={() => onUnequipItem('hands')}
            />

            {/* Primary Weapon */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.weapon1}
              item={getSlotItem(hero.equipment.weapon1)}
              onUnequip={() => onUnequipItem('weapon1')}
            />

            {/* Boots */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.feet}
              item={getSlotItem(hero.equipment.feet)}
              onUnequip={() => onUnequipItem('feet')}
            />

            {/* Secondary Weapon */}
            <EquipmentSlotCard
              label={STRINGS.inventory.slots.weapon2}
              item={getSlotItem(hero.equipment.weapon2)}
              onUnequip={() => onUnequipItem('weapon2')}
            />
          </div>
        </div>

        {/* Right: Backpack Luggage Grid & Filters (7 cols) */}
        <div className="lg:col-span-7 bg-stone-900/80 border border-stone-800 rounded-lg p-3.5 flex flex-col space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-xs font-bold text-amber-400 uppercase tracking-wider">
              Luggage Wagons
            </span>

            {/* Category Filter */}
            <div className="flex items-center gap-1 bg-stone-950 rounded p-1 border border-stone-800 text-xs">
              {(['all', 'weapon', 'armor', 'food', 'trade'] as const).map((cat) => (
                <button
                  key={cat}
                  onClick={() => {
                    sound.playClick();
                    setFilterType(cat);
                  }}
                  className={`px-2.5 py-1 rounded capitalize font-medium min-h-[32px] ${
                    filterType === cat
                      ? 'bg-amber-700 text-stone-100 font-bold'
                      : 'text-stone-400 hover:text-stone-200'
                  }`}
                >
                  {cat}
                </button>
              ))}
            </div>
          </div>

          {/* Items Grid */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 flex-1 max-h-[50vh] overflow-y-auto pr-1">
            {hero.inventory.length === 0 ? (
              <p className="text-xs text-stone-500 italic p-4 col-span-2 text-center">
                Your luggage contains no items. Visit settlement markets to purchase goods.
              </p>
            ) : (
              hero.inventory
                .filter((item) => {
                  const def = ITEMS[item.id];
                  if (!def) return false;
                  if (filterType === 'food') return def.type === 'food';
                  if (filterType === 'trade') return def.type === 'tradeGood';
                  if (filterType === 'weapon')
                    return ['oneHanded', 'twoHanded', 'polearm', 'bow', 'throwing'].includes(def.type);
                  if (filterType === 'armor')
                    return ['head', 'body', 'hands', 'feet', 'shield', 'horse'].includes(def.type);
                  return true;
                })
                .map((invItem) => {
                  const def = ITEMS[invItem.id];
                  if (!def) return null;
                  const isSelected = selectedItem?.id === def.id;

                  return (
                    <button
                      key={invItem.id}
                      onClick={() => handleBackpackClick(invItem.id)}
                      className={`p-2.5 rounded border text-left flex items-center justify-between transition-all min-h-[48px] cursor-pointer ${
                        isSelected
                          ? 'border-amber-400 bg-stone-800'
                          : 'border-stone-800 bg-stone-950/60 hover:border-stone-700'
                      }`}
                    >
                      <div className="flex-1 pr-2">
                        <span className="font-bold text-xs text-stone-100 block">
                          {def.name} <span className="text-amber-400 font-mono">(x{invItem.count})</span>
                        </span>
                        <span className="text-[10px] text-stone-400 capitalize">
                          {def.type} · {(def.weight * invItem.count).toFixed(1)}kg
                        </span>
                      </div>
                      <span className="text-xs font-mono text-amber-300 font-bold">
                        {def.value} D
                      </span>
                    </button>
                  );
                })
            )}
          </div>

          {/* Selected Item Inspection Bar */}
          {selectedItem && (
            <div className="p-3 bg-stone-950 border border-stone-800 rounded-lg flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs">
              <div>
                <span className="font-bold text-amber-200 text-sm">{selectedItem.name}</span>
                <p className="text-stone-400 text-[11px] line-clamp-1">{selectedItem.description}</p>
                <div className="flex gap-3 text-[10px] text-stone-300 mt-1">
                  {selectedItem.damage && <span>Damage: +{selectedItem.damage}</span>}
                  {selectedItem.armor && <span>Armor: +{selectedItem.armor}</span>}
                  {selectedItem.foodDays && <span>Provisions: {selectedItem.foodDays} days</span>}
                  <span>Weight: {selectedItem.weight}kg</span>
                  <span>Value: {selectedItem.value} Gold</span>
                </div>
              </div>

              <div className="flex items-center gap-2">
                {selectedItem.type !== 'food' && selectedItem.type !== 'tradeGood' && (
                  <button
                    onClick={equipSelected}
                    className="py-1.5 px-3 rounded bg-amber-700 hover:bg-amber-600 text-amber-100 font-bold min-h-[36px] cursor-pointer"
                  >
                    {STRINGS.inventory.equip}
                  </button>
                )}
                <button
                  onClick={() => {
                    onDropItem(selectedItem.id);
                    setSelectedItem(null);
                  }}
                  className="py-1.5 px-3 rounded bg-stone-800 hover:bg-stone-700 border border-stone-700 text-stone-300 font-semibold min-h-[36px] cursor-pointer"
                >
                  {STRINGS.inventory.drop}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

const EquipmentSlotCard: React.FC<{
  label: string;
  item: ItemDef | null;
  onUnequip: () => void;
}> = ({ label, item, onUnequip }) => {
  return (
    <div className="p-2.5 rounded bg-stone-950/70 border border-stone-800 flex flex-col justify-between min-h-[64px]">
      <span className="text-[10px] text-stone-500 font-bold uppercase">{label}</span>
      {item ? (
        <div className="flex items-center justify-between mt-1">
          <div>
            <span className="font-bold text-amber-200 text-xs block">{item.name}</span>
            <span className="text-[10px] text-stone-400">
              {item.damage ? `Dmg: ${item.damage}` : item.armor ? `Arm: ${item.armor}` : ''}
            </span>
          </div>
          <button
            onClick={onUnequip}
            className="text-[10px] text-stone-400 hover:text-stone-200 underline p-1"
          >
            Unequip
          </button>
        </div>
      ) : (
        <span className="text-xs text-stone-600 italic">Empty Slot</span>
      )}
    </div>
  );
};
