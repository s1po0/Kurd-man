/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { FactionId, HeroData } from '../core/types';

export interface CompanionDef {
  id: string;
  name: string;
  title: string;
  culture: FactionId;
  hireCost: number;
  dailyWage: number;
  backstory: string;
  heroData: Partial<HeroData>;
}

export const COMPANIONS: CompanionDef[] = [
  {
    id: 'comp_erik',
    name: 'Erik the Iron-Forged',
    title: 'Disgraced Master Smith',
    culture: 'ironmark',
    hireCost: 250,
    dailyWage: 8,
    backstory: 'Exiled from the High Bastion after refusing to forge blades for a corrupt guildmaster. Knows the balance of steel and how to maintain war equipment in field camps.',
    heroData: {
      attributes: { vigor: 5, control: 3, endurance: 4, cunning: 3, social: 2, intelligence: 5 },
      skills: {
        oneHanded: 45,
        twoHanded: 60,
        polearm: 20,
        bow: 15,
        throwing: 20,
        riding: 25,
        athletics: 40,
        tactics: 30,
        scouting: 20,
        leadership: 25,
        trade: 40,
        steward: 55,
        medicine: 30,
      },
    },
  },
  {
    id: 'comp_rowan',
    name: 'Sister Rowan',
    title: 'Hedge Herbalist',
    culture: 'dunmere',
    hireCost: 200,
    dailyWage: 6,
    backstory: 'Learned the art of staunching wounds and boiling poultices in the mist marshes. Her swift hands have kept scores of bleeding soldiers from the grave.',
    heroData: {
      attributes: { vigor: 2, control: 4, endurance: 3, cunning: 4, social: 4, intelligence: 6 },
      skills: {
        oneHanded: 20,
        twoHanded: 10,
        polearm: 25,
        bow: 45,
        throwing: 15,
        riding: 30,
        athletics: 35,
        tactics: 25,
        scouting: 45,
        leadership: 30,
        trade: 30,
        steward: 40,
        medicine: 75,
      },
    },
  },
  {
    id: 'comp_nadia',
    name: 'Nadia the Wind-Rider',
    title: 'Outlaw Horse Archer',
    culture: 'sunreach',
    hireCost: 350,
    dailyWage: 12,
    backstory: 'A nomad marksman whose clan was scattered by a blood feud. Able to hit a soaring hawk while galloping at full tilt across shifting sands.',
    heroData: {
      attributes: { vigor: 3, control: 6, endurance: 5, cunning: 4, social: 3, intelligence: 2 },
      skills: {
        oneHanded: 35,
        twoHanded: 15,
        polearm: 30,
        bow: 75,
        throwing: 40,
        riding: 70,
        athletics: 50,
        tactics: 35,
        scouting: 60,
        leadership: 20,
        trade: 25,
        steward: 20,
        medicine: 20,
      },
    },
  },
  {
    id: 'comp_valentin',
    name: 'Sir Valentin of Oakhaven',
    title: 'Lover of Quests',
    culture: 'vaeloria',
    hireCost: 400,
    dailyWage: 14,
    backstory: 'Second son of a minor baron who took vows of knight-errantry. He possesses a noble bearing, fine horsemanship, and unflinching martial courage.',
    heroData: {
      attributes: { vigor: 5, control: 3, endurance: 4, cunning: 3, social: 5, intelligence: 3 },
      skills: {
        oneHanded: 65,
        twoHanded: 30,
        polearm: 70,
        bow: 20,
        throwing: 15,
        riding: 65,
        athletics: 40,
        tactics: 45,
        scouting: 25,
        leadership: 55,
        trade: 20,
        steward: 30,
        medicine: 25,
      },
    },
  },
];
