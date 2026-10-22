/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

export type FactionId = 'vaeloria' | 'ironmark' | 'dunmere' | 'sunreach' | 'frostholm';

export type TerrainType = 'plains' | 'forest' | 'hills' | 'mountains' | 'water' | 'desert' | 'snow' | 'road';

export type SettlementType = 'town' | 'village' | 'castle';

export type PartyType = 'player' | 'lord' | 'patrol' | 'caravan' | 'bandit';

export type TroopRole = 'infantry' | 'spear' | 'archer' | 'cavalry';

export type ItemType =
  | 'oneHanded'
  | 'twoHanded'
  | 'polearm'
  | 'bow'
  | 'throwing'
  | 'shield'
  | 'head'
  | 'body'
  | 'hands'
  | 'feet'
  | 'horse'
  | 'tradeGood'
  | 'food';

export type AttributeKey = 'vigor' | 'control' | 'endurance' | 'cunning' | 'social' | 'intelligence';

export type SkillKey =
  | 'oneHanded'
  | 'twoHanded'
  | 'polearm'
  | 'bow'
  | 'throwing'
  | 'riding'
  | 'athletics'
  | 'tactics'
  | 'scouting'
  | 'leadership'
  | 'trade'
  | 'steward'
  | 'medicine';

export interface AttributeDef {
  name: string;
  description: string;
  governedSkills: SkillKey[];
}

export interface SkillDef {
  name: string;
  attribute: AttributeKey;
  description: string;
}

export interface FactionDef {
  id: FactionId;
  name: string;
  adjective: string;
  rulerName: string;
  rulerTitle: string;
  lore: string;
  primaryColor: string;
  secondaryColor: string;
  accentColor: string;
  bannerSymbol: string;
  culturalTrait: {
    title: string;
    description: string;
  };
}

export interface TroopDef {
  id: string;
  culture: FactionId | 'mercenary' | 'bandit';
  name: string;
  tier: 1 | 2 | 3;
  role: TroopRole;
  hp: number;
  attack: number;
  defense: number;
  speed: number;
  wage: number;
  xpToUpgrade: number;
  upgradeTo?: string[];
  equipmentDesc: string;
}

export interface PartyTroopStack {
  troopId: string;
  count: number;
  woundedCount: number;
  xp: number;
}

export interface ItemDef {
  id: string;
  name: string;
  type: ItemType;
  tier: 1 | 2 | 3;
  weight: number;
  value: number;
  damage?: number;
  armor?: number;
  speedBonus?: number;
  foodDays?: number; // For food items: feeds party
  moraleBonus?: number;
  description: string;
}

export interface InventoryItem {
  id: string;
  count: number;
}

export interface EquipmentSlots {
  head?: string;
  body?: string;
  hands?: string;
  feet?: string;
  weapon1?: string;
  weapon2?: string;
  shieldOrArrows?: string;
  mount?: string;
}

export interface CharacterAttributes {
  vigor: number;
  control: number;
  endurance: number;
  cunning: number;
  social: number;
  intelligence: number;
}

export interface CharacterSkills {
  oneHanded: number;
  twoHanded: number;
  polearm: number;
  bow: number;
  throwing: number;
  riding: number;
  athletics: number;
  tactics: number;
  scouting: number;
  leadership: number;
  trade: number;
  steward: number;
  medicine: number;
}

export interface HeroData {
  id: string;
  name: string;
  culture: FactionId;
  backgroundOrigin: string;
  gender: 'male' | 'female';
  hp: number;
  maxHp: number;
  gold: number;
  renown: number;
  honor: number;
  attributes: CharacterAttributes;
  skills: CharacterSkills;
  attributePoints: number;
  skillPoints: number;
  equipment: EquipmentSlots;
  inventory: InventoryItem[];
  bannerHeraldry: {
    symbolIndex: number;
    bgPatternIndex: number;
    primaryColor: string;
    secondaryColor: string;
  };
}

export interface SettlementData {
  id: string;
  name: string;
  type: SettlementType;
  factionId: FactionId;
  x: number;
  y: number;
  prosperity: number;
  garrison: PartyTroopStack[];
  recruitsAvailable: PartyTroopStack[];
  lastRecruitRefreshDay: number;
  marketGoods: Record<string, { quantity: number; buyPrice: number; sellPrice: number }>;
  boundTownId?: string; // For villages, the town they supply
}

export interface PartyData {
  id: string;
  name: string;
  partyType: PartyType;
  factionId: FactionId | 'neutral' | 'bandit';
  leaderName: string;
  x: number;
  y: number;
  targetX?: number;
  targetY?: number;
  path: { x: number; y: number }[];
  troops: PartyTroopStack[];
  prisoners: PartyTroopStack[];
  gold: number;
  morale: number;
  foodDays: number;
  speed: number;
  behavior: 'idle' | 'patrol' | 'travel' | 'flee' | 'pursue';
  behaviorTargetId?: string;
  destinationSettlementId?: string;
  lastDecisionTime: number;
}

export interface CampaignTime {
  day: number;
  month: number; // 1-12
  year: number;
  hour: number; // 0-23
  minute: number; // 0-59
  timeSpeed: 0 | 1 | 2 | 4;
}

export interface TileCoord {
  x: number;
  y: number;
}

export interface MapData {
  width: number;
  height: number;
  seed: number;
  tiles: TerrainType[][];
  roads: boolean[][];
  settlements: SettlementData[];
}

export type GameView =
  | 'TITLE'
  | 'CREATION'
  | 'CAMPAIGN'
  | 'SETTLEMENT'
  | 'ENCOUNTER'
  | 'PARTY_MENU'
  | 'INVENTORY_MENU'
  | 'CHARACTER_SHEET'
  | 'SETTINGS'
  | 'SAVELOAD'
  | 'TUTORIAL';

export interface GameSettings {
  soundMuted: boolean;
  masterVolume: number; // 0.0 - 1.0
  musicVolume: number;
  sfxVolume: number;
  dprCap: number; // 1 or 2
  showFps: boolean;
  fogOfWar: boolean;
  difficulty: 'easy' | 'normal' | 'hard';
}

export interface SaveGameMetadata {
  slot: number; // 0 = autosave, 1, 2, 3 = manual
  saveName: string;
  timestamp: number;
  playtimeSeconds: number;
  heroName: string;
  heroCulture: FactionId;
  day: number;
  gold: number;
  partySize: number;
  version: number;
}

export interface SaveGameState {
  version: number;
  metadata: SaveGameMetadata;
  hero: HeroData;
  playerParty: PartyData;
  aiParties: PartyData[];
  settlements: SettlementData[];
  time: CampaignTime;
  mapSeed: number;
  exploredTiles: boolean[][];
  questFlags: Record<string, boolean>;
  settings: GameSettings;
}
