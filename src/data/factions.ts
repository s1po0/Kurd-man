/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { FactionDef, FactionId } from '../core/types';

export const FACTIONS: Record<FactionId, FactionDef> = {
  vaeloria: {
    id: 'vaeloria',
    name: 'Grand Realm of Vaeloria',
    adjective: 'Vaelorian',
    rulerName: 'King Alden IV the Just',
    rulerTitle: 'Sovereign of the Silver Throne',
    lore: 'An ancient feudal kingdom of chivalric chivalry, towering stone citadels, and armored knights whose devastating lance charges have shattered invading hosts for three centuries.',
    primaryColor: '#1e3a8a', // Royal Blue
    secondaryColor: '#f59e0b', // Gold
    accentColor: '#e0e7ff', // Silver/White
    bannerSymbol: 'lion',
    culturalTrait: {
      title: 'Knightly Chivalry',
      description: '+15% Cavalry charge momentum and +10% maximum party morale on open plains.',
    },
  },
  ironmark: {
    id: 'ironmark',
    name: 'Ironmark Federation',
    adjective: 'Ironborn',
    rulerName: 'Grand Warden Kaelen Stone-Fist',
    rulerTitle: 'Lord Commander of the High Bastion',
    lore: 'A confederation of mountain fortresses and deep mines. The Ironborn disdain mounted combat, trusting in heavy plate armor, impenetrable shield walls, and crushing two-handed war cleavers.',
    primaryColor: '#881337', // Crimson Red
    secondaryColor: '#475569', // Iron Slate
    accentColor: '#cbd5e1', // Steel
    bannerSymbol: 'hammer',
    culturalTrait: {
      title: 'Mountain Resilience',
      description: '-20% troop recruitment costs and infantry receives +15% armor effectiveness.',
    },
  },
  dunmere: {
    id: 'dunmere',
    name: 'Dunmere Sovereignty',
    adjective: 'Dunmerian',
    rulerName: 'Queen Maeve of the Mire',
    rulerTitle: 'Matriarch of the Greenwood',
    lore: 'Dwellers of the whispering mist forests and river delta marshes. Renowned for elusive longbowmen and peltasts who shoot lethal volleys from dense cover and vanish into the brush.',
    primaryColor: '#064e3b', // Deep Forest Emerald
    secondaryColor: '#d97706', // Autumn Amber
    accentColor: '#a7f3d0', // Mint
    bannerSymbol: 'tree',
    culturalTrait: {
      title: 'Woodland Wayfarers',
      description: '+25% travel speed through forest terrain and +15% archery damage.',
    },
  },
  sunreach: {
    id: 'sunreach',
    name: 'Solarate of Sunreach',
    adjective: 'Sunreached',
    rulerName: 'Padishah Tariq Ibn-Zayd',
    rulerTitle: 'Emir of the Golden Sands',
    lore: 'A wealthy merchant empire stretching across arid dune seas and oasis ports. Their armies field swift desert coursers, deadly composite horse archers, and curved bronze scimitars.',
    primaryColor: '#b45309', // Desert Gold
    secondaryColor: '#78350f', // Sun Bronze
    accentColor: '#fef08a', // Bright Sand
    bannerSymbol: 'sun',
    culturalTrait: {
      title: 'Silk & Sand Masters',
      description: '+20% travel speed in arid terrain and +25% trade caravan profit margins.',
    },
  },
  frostholm: {
    id: 'frostholm',
    name: 'Frostholm Jarls',
    adjective: 'Frostholmer',
    rulerName: 'High Jarl Torvald Blood-Axe',
    rulerTitle: 'Warlord of the Frozen Fjords',
    lore: 'Hardy sea-wolves and axe-wielding huscarls from the frozen tundra and coastal cliffs. Feared for ferocious berserker charges and endurance in harsh winter blizzards.',
    primaryColor: '#0e7490', // Deep Fjord Cyan
    secondaryColor: '#0f172a', // Midnight Navy
    accentColor: '#e0f2fe', // Glacial Ice
    bannerSymbol: 'wolf',
    culturalTrait: {
      title: 'Frostborn Fury',
      description: 'Zero speed penalty in snow terrain and warriors gain +20% melee shock damage.',
    },
  },
};

export const FACTION_LIST: FactionDef[] = Object.values(FACTIONS);
