/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { AttributeDef, AttributeKey, SkillDef, SkillKey, TerrainType } from './types';

export const MAP_CONFIG = {
  width: 90,
  height: 60,
  tileSize: 40, // Base pixel size of a tile at 1x zoom
  minZoom: 0.6,
  maxZoom: 2.4,
  defaultZoom: 1.5,
  visionRadius: 10, // Fog of war vision radius in tiles
};

export const TERRAIN_COSTS: Record<TerrainType, number> = {
  road: 0.75,
  plains: 1.0,
  forest: 1.5,
  hills: 2.0,
  desert: 1.35,
  snow: 1.6,
  mountains: 9999, // Impassable
  water: 9999, // Impassable
};

export const TERRAIN_COLORS: Record<TerrainType, { base: string; accent: string; detail: string }> = {
  plains: { base: '#43683a', accent: '#4e7543', detail: '#385830' },
  forest: { base: '#254425', accent: '#1b331b', detail: '#2f522e' },
  hills: { base: '#706c4b', accent: '#5c583c', detail: '#837e58' },
  mountains: { base: '#575659', accent: '#3f3e40', detail: '#7b7a7d' },
  water: { base: '#2b5278', accent: '#21405e', detail: '#386694' },
  desert: { base: '#b0945d', accent: '#99804e', detail: '#c7a96e' },
  snow: { base: '#d3dde4', accent: '#b9c7cf', detail: '#e6eef2' },
  road: { base: '#877355', accent: '#736147', detail: '#9e8765' },
};

export const TIME_CONFIG = {
  realSecondsPerGameDay: 36, // 36 real seconds per full in-game 24h at 1x speed
  hoursPerDay: 24,
  daysPerMonth: 30,
  monthsPerYear: 4, // 4 seasons: Spring, Summer, Autumn, Winter
};

export const MONTH_NAMES = [
  'Bloomtide (Spring)',
  'Sunreach (Summer)',
  'Goldharvest (Autumn)',
  'Frostfall (Winter)',
];

export const ATTRIBUTES: Record<AttributeKey, AttributeDef> = {
  vigor: {
    name: 'Vigor',
    description: 'Physical prowess and muscular strength. Increases melee weapon damage and party carry capacity.',
    governedSkills: ['oneHanded', 'twoHanded', 'polearm'],
  },
  control: {
    name: 'Control',
    description: 'Fine motor coordination and precision. Enhances ranged weapon accuracy, draw speed, and reload.',
    governedSkills: ['bow', 'throwing'],
  },
  endurance: {
    name: 'Endurance',
    description: 'Stamina, grit, and physical resilience. Governs movement speed on foot and horseback, and maximum health.',
    governedSkills: ['riding', 'athletics'],
  },
  cunning: {
    name: 'Cunning',
    description: 'Tactical intellect and battlefield perception. Improves scout vision, ambush avoidance, and army maneuvers.',
    governedSkills: ['scouting', 'tactics'],
  },
  social: {
    name: 'Social',
    description: 'Charisma, eloquence, and personal magnetism. Boosts troop morale, recruitment rates, and trade margins.',
    governedSkills: ['leadership', 'trade'],
  },
  intelligence: {
    name: 'Intelligence',
    description: 'Administrative foresight and medical knowledge. Reduces troop wages, handles fiefs, and saves wounded warriors.',
    governedSkills: ['steward', 'medicine'],
  },
};

export const SKILLS: Record<SkillKey, SkillDef> = {
  oneHanded: {
    name: 'One-Handed',
    attribute: 'vigor',
    description: 'Mastery of swords, axes, and maces used alongside a shield or offhand weapon.',
  },
  twoHanded: {
    name: 'Two-Handed',
    attribute: 'vigor',
    description: 'Skill with devastating claymores, executioner axes, and two-handed warhammers.',
  },
  polearm: {
    name: 'Polearm',
    attribute: 'vigor',
    description: 'Expertise with lances, pikes, spears, and glaives. Devastating on horseback.',
  },
  bow: {
    name: 'Archery',
    attribute: 'control',
    description: 'Deadly accuracy and draw strength with hunting bows, composite bows, and longbows.',
  },
  throwing: {
    name: 'Throwing',
    attribute: 'control',
    description: 'Lethal precision with throwing spears, javelins, and balanced throwing axes.',
  },
  riding: {
    name: 'Riding',
    attribute: 'endurance',
    description: 'Horsemanship and mounted balance. Grants speed bonuses on the world map and in combat.',
  },
  athletics: {
    name: 'Athletics',
    attribute: 'endurance',
    description: 'Foot speed, stamina regeneration, and personal physical toughness.',
  },
  tactics: {
    name: 'Tactics',
    attribute: 'cunning',
    description: 'Battlefield command expertise. Increases simulated skirmish advantages and lowers retreat casualties.',
  },
  scouting: {
    name: 'Scouting',
    attribute: 'cunning',
    description: 'Track identification, spotting distance on the campaign map, and movement efficiency in dense terrain.',
  },
  leadership: {
    name: 'Leadership',
    attribute: 'social',
    description: 'Inspirational presence. Increases party maximum size, boosts morale, and reduces wage demands.',
  },
  trade: {
    name: 'Trade',
    attribute: 'social',
    description: 'Mercantile acumen. Decreases trade penalty fees, highlights profitable trade routes, and increases barter prices.',
  },
  steward: {
    name: 'Stewardship',
    attribute: 'intelligence',
    description: 'Quartermaster logistics. Increases party capacity, reduces food consumption, and governs fief prosperity.',
  },
  medicine: {
    name: 'Medicine',
    attribute: 'intelligence',
    description: 'Battlefield surgery. Vastly increases survival chance of fallen troops (converting deaths to wounded) and speeds recovery.',
  },
};
