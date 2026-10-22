/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { MAP_CONFIG } from '../core/constants';
import { SeededRNG } from '../core/rng';
import { MapData, SettlementData, TerrainType } from '../core/types';
import { ITEMS } from '../data/items';

/**
 * 2D Value Noise generator with octaves and bicubic interpolation.
 */
class ValueNoise2D {
  private values: number[][];
  private size: number;

  constructor(size: number, rng: SeededRNG) {
    this.size = size;
    this.values = [];
    for (let y = 0; y < size; y++) {
      const row: number[] = [];
      for (let x = 0; x < size; x++) {
        row.push(rng.next());
      }
      this.values.push(row);
    }
  }

  private smooth(t: number): number {
    return t * t * (3 - 2 * t);
  }

  sample(x: number, y: number): number {
    const s = this.size;
    const x0 = Math.floor(x) % s;
    const y0 = Math.floor(y) % s;
    const x1 = (x0 + 1) % s;
    const y1 = (y0 + 1) % s;

    const fx = this.smooth(x - Math.floor(x));
    const fy = this.smooth(y - Math.floor(y));

    const top = (1 - fx) * this.values[y0][x0] + fx * this.values[y0][x1];
    const bot = (1 - fx) * this.values[y1][x0] + fx * this.values[y1][x1];

    return (1 - fy) * top + fy * bot;
  }

  octave(x: number, y: number, octaves: number, persistence: number): number {
    let total = 0;
    let frequency = 1;
    let amplitude = 1;
    let maxValue = 0;

    for (let i = 0; i < octaves; i++) {
      total += this.sample(x * frequency, y * frequency) * amplitude;
      maxValue += amplitude;
      amplitude *= persistence;
      frequency *= 2;
    }

    return total / maxValue;
  }
}

/**
 * Generate the world map with settlements, terrain, and roads.
 */
export function generateCampaignMap(seed: number = 4281): MapData {
  const rng = new SeededRNG(seed);
  const width = MAP_CONFIG.width;
  const height = MAP_CONFIG.height;

  const elevationNoise = new ValueNoise2D(32, rng);
  const moistureNoise = new ValueNoise2D(32, rng);
  const detailNoise = new ValueNoise2D(32, rng);

  const tiles: TerrainType[][] = [];
  const roads: boolean[][] = [];

  for (let y = 0; y < height; y++) {
    const tileRow: TerrainType[] = [];
    const roadRow: boolean[] = [];

    for (let x = 0; x < width; x++) {
      // Coordinate normalized (0..1)
      const nx = x / width;
      const ny = y / height;

      // Distance from map edge to create natural ocean coastline boundaries
      const edgeDistX = Math.min(x, width - 1 - x) / (width * 0.12);
      const edgeDistY = Math.min(y, height - 1 - y) / (height * 0.12);
      const edgeFalloff = Math.max(0, Math.min(1, Math.min(edgeDistX, edgeDistY)));

      const elev = elevationNoise.octave(nx * 5, ny * 5, 4, 0.5) * edgeFalloff;
      const moist = moistureNoise.octave(nx * 4, ny * 4, 3, 0.5);
      const detail = detailNoise.octave(nx * 10, ny * 10, 2, 0.5);

      let terrain: TerrainType = 'plains';

      // Coastlines / Lakes
      if (elev < 0.2) {
        terrain = 'water';
      } else if (elev > 0.72) {
        // High Mountain Ranges & Ridges
        terrain = 'mountains';
      } else if (elev > 0.58) {
        // Foothills & Rocky Crags
        terrain = 'hills';
      } else {
        // Biomes based on Latitude (ny) and Moisture (moist)
        if (ny < 0.28) {
          // Northern Frigid Lands (Frostholm)
          if (moist > 0.6) terrain = 'forest';
          else if (elev > 0.45) terrain = 'hills';
          else terrain = 'snow';
        } else if (ny > 0.72) {
          // Southern Arid Solarate (Sunreach)
          if (moist > 0.65) terrain = 'plains';
          else if (elev > 0.5) terrain = 'hills';
          else terrain = 'desert';
        } else if (nx < 0.35) {
          // Western Greenwood & Mist Marshes (Dunmere)
          if (moist > 0.4 || detail > 0.45) terrain = 'forest';
          else if (elev > 0.5) terrain = 'hills';
          else terrain = 'plains';
        } else if (nx > 0.68) {
          // Eastern Highland Rim (Ironmark)
          if (elev > 0.42 || detail > 0.55) terrain = 'hills';
          else if (moist > 0.65) terrain = 'forest';
          else terrain = 'plains';
        } else {
          // Central Heartland (Vaeloria) - fertile plains with scattered groves and hills
          if (moist > 0.65 && detail > 0.4) {
            terrain = 'forest';
          } else if (elev > 0.48 && detail > 0.6) {
            terrain = 'hills';
          } else {
            terrain = 'plains';
          }
        }
      }

      tileRow.push(terrain);
      roadRow.push(false);
    }
    tiles.push(tileRow);
    roads.push(roadRow);
  }

  // Pre-designed settlements positioned strategically across factions
  const initialSettlements: Omit<SettlementData, 'marketGoods' | 'garrison' | 'recruitsAvailable' | 'lastRecruitRefreshDay'>[] = [
    // --- 6 TOWNS (Major Faction Capitals & Trade Centers) ---
    {
      id: 'town_vaelor',
      name: 'Vaelor-on-the-Hill',
      type: 'town',
      factionId: 'vaeloria',
      x: 44,
      y: 28,
      prosperity: 78,
    },
    {
      id: 'town_bastion',
      name: 'High Bastion Citadel',
      type: 'town',
      factionId: 'ironmark',
      x: 72,
      y: 32,
      prosperity: 72,
    },
    {
      id: 'town_greenhaven',
      name: 'Greenhaven Port',
      type: 'town',
      factionId: 'dunmere',
      x: 18,
      y: 25,
      prosperity: 68,
    },
    {
      id: 'town_sun_oasis',
      name: 'Al-Khadra Oasis',
      type: 'town',
      factionId: 'sunreach',
      x: 52,
      y: 49,
      prosperity: 82,
    },
    {
      id: 'town_frostfjord',
      name: 'Frostfjord Hold',
      type: 'town',
      factionId: 'frostholm',
      x: 42,
      y: 11,
      prosperity: 64,
    },
    {
      id: 'town_crossroads',
      name: 'Goldcross Free City',
      type: 'town',
      factionId: 'vaeloria',
      x: 35,
      y: 38,
      prosperity: 75,
    },

    // --- 5 CASTLES ---
    {
      id: 'castle_silverguard',
      name: 'Silverguard Castle',
      type: 'castle',
      factionId: 'vaeloria',
      x: 50,
      y: 22,
      prosperity: 55,
    },
    {
      id: 'castle_ironpeak',
      name: 'Ironpeak Redoubt',
      type: 'castle',
      factionId: 'ironmark',
      x: 78,
      y: 22,
      prosperity: 52,
    },
    {
      id: 'castle_moss_watch',
      name: 'Moss Watch Fortress',
      type: 'castle',
      factionId: 'dunmere',
      x: 14,
      y: 36,
      prosperity: 50,
    },
    {
      id: 'castle_dune_bastion',
      name: 'Dune Fang Outpost',
      type: 'castle',
      factionId: 'sunreach',
      x: 65,
      y: 46,
      prosperity: 54,
    },
    {
      id: 'castle_grim_fang',
      name: 'Grimfang Keep',
      type: 'castle',
      factionId: 'frostholm',
      x: 28,
      y: 13,
      prosperity: 48,
    },

    // --- 12 VILLAGES ---
    {
      id: 'vil_oakhaven',
      name: 'Oakhaven',
      type: 'village',
      factionId: 'vaeloria',
      boundTownId: 'town_vaelor',
      x: 48,
      y: 31,
      prosperity: 45,
    },
    {
      id: 'vil_wheatbrook',
      name: 'Wheatbrook',
      type: 'village',
      factionId: 'vaeloria',
      boundTownId: 'town_vaelor',
      x: 39,
      y: 26,
      prosperity: 50,
    },
    {
      id: 'vil_stonecrag',
      name: 'Stonecrag Quarry',
      type: 'village',
      factionId: 'ironmark',
      boundTownId: 'town_bastion',
      x: 68,
      y: 36,
      prosperity: 42,
    },
    {
      id: 'vil_highforge',
      name: 'Highforge',
      type: 'village',
      factionId: 'ironmark',
      boundTownId: 'town_bastion',
      x: 76,
      y: 28,
      prosperity: 46,
    },
    {
      id: 'vil_willow_creek',
      name: 'Willow Creek',
      type: 'village',
      factionId: 'dunmere',
      boundTownId: 'town_greenhaven',
      x: 23,
      y: 22,
      prosperity: 44,
    },
    {
      id: 'vil_reedmere',
      name: 'Reedmere Fishery',
      type: 'village',
      factionId: 'dunmere',
      boundTownId: 'town_greenhaven',
      x: 15,
      y: 30,
      prosperity: 48,
    },
    {
      id: 'vil_dune_well',
      name: 'Bir-Salam Wells',
      type: 'village',
      factionId: 'sunreach',
      boundTownId: 'town_sun_oasis',
      x: 48,
      y: 53,
      prosperity: 45,
    },
    {
      id: 'vil_palm_grove',
      name: 'Palm Grove',
      type: 'village',
      factionId: 'sunreach',
      boundTownId: 'town_sun_oasis',
      x: 58,
      y: 51,
      prosperity: 52,
    },
    {
      id: 'vil_fjord_harrow',
      name: 'Fjordharrow',
      type: 'village',
      factionId: 'frostholm',
      boundTownId: 'town_frostfjord',
      x: 36,
      y: 9,
      prosperity: 40,
    },
    {
      id: 'vil_wolf_glen',
      name: 'Wolfglen',
      type: 'village',
      factionId: 'frostholm',
      boundTownId: 'town_frostfjord',
      x: 47,
      y: 14,
      prosperity: 42,
    },
    {
      id: 'vil_fairfields',
      name: 'Fairfields Mill',
      type: 'village',
      factionId: 'vaeloria',
      boundTownId: 'town_crossroads',
      x: 30,
      y: 41,
      prosperity: 54,
    },
    {
      id: 'vil_amber_falls',
      name: 'Amberfalls',
      type: 'village',
      factionId: 'vaeloria',
      boundTownId: 'town_crossroads',
      x: 40,
      y: 41,
      prosperity: 49,
    },
  ];

  // Guarantee all settlement tiles are passable plains or road
  const settlements: SettlementData[] = initialSettlements.map((s) => {
    tiles[s.y][s.x] = 'plains';

    // Generate initial market goods with regional pricing differences
    const marketGoods: SettlementData['marketGoods'] = {};
    for (const item of Object.values(ITEMS)) {
      let baseQty = 10;
      let priceMod = 1.0;

      // Regional commodity surpluses & scarcities
      if (item.id === 'trade_iron_ore' || item.id === 'trade_tools') {
        if (s.factionId === 'ironmark') {
          priceMod = 0.55; // Abundant & cheap in Ironmark
          baseQty = 35;
        } else {
          priceMod = 1.45; // High demand everywhere else
        }
      } else if (item.id === 'trade_velvet' || item.id === 'trade_spices') {
        if (s.factionId === 'sunreach') {
          priceMod = 0.5;
          baseQty = 30;
        } else {
          priceMod = 1.7; // Very profitable in the north!
        }
      } else if (item.id === 'trade_furs' || item.id === 'food_fish') {
        if (s.factionId === 'frostholm') {
          priceMod = 0.55;
          baseQty = 35;
        } else if (s.factionId === 'sunreach') {
          priceMod = 1.8;
        }
      } else if (item.id === 'trade_wine' || item.id === 'food_grain') {
        if (s.factionId === 'vaeloria') {
          priceMod = 0.65;
          baseQty = 40;
        } else {
          priceMod = 1.35;
        }
      } else if (item.id === 'trade_linen' || item.id === 'trade_wool') {
        if (s.factionId === 'dunmere') {
          priceMod = 0.6;
          baseQty = 35;
        } else {
          priceMod = 1.4;
        }
      }

      const buyPrice = Math.max(1, Math.round(item.value * priceMod * 1.15));
      const sellPrice = Math.max(1, Math.round(item.value * priceMod * 0.85));

      marketGoods[item.id] = {
        quantity: s.type === 'village' ? Math.floor(baseQty * 0.4) : baseQty,
        buyPrice,
        sellPrice,
      };
    }

    // Default garrison for towns and castles
    const garrison = s.type === 'town'
      ? [
          { troopId: `${s.factionId === 'vaeloria' ? 'vael' : s.factionId === 'ironmark' ? 'iron' : s.factionId === 'dunmere' ? 'dun' : s.factionId === 'sunreach' ? 'sun' : 'frost'}_footman`, count: 25, woundedCount: 0, xp: 0 },
          { troopId: `${s.factionId === 'vaeloria' ? 'vael' : s.factionId === 'ironmark' ? 'iron' : s.factionId === 'dunmere' ? 'dun' : s.factionId === 'sunreach' ? 'sun' : 'frost'}_recruit`, count: 40, woundedCount: 0, xp: 0 },
        ]
      : s.type === 'castle'
      ? [
          { troopId: `${s.factionId === 'vaeloria' ? 'vael' : s.factionId === 'ironmark' ? 'iron' : s.factionId === 'dunmere' ? 'dun' : s.factionId === 'sunreach' ? 'sun' : 'frost'}_recruit`, count: 20, woundedCount: 0, xp: 0 },
        ]
      : [];

    // Recruits available
    const recruitPrefix = s.factionId === 'vaeloria' ? 'vael' : s.factionId === 'ironmark' ? 'iron' : s.factionId === 'dunmere' ? 'dun' : s.factionId === 'sunreach' ? 'sun' : 'frost';
    const recruitsAvailable = [
      {
        troopId: `${recruitPrefix}_recruit`,
        count: s.type === 'village' ? 8 : 14,
        woundedCount: 0,
        xp: 0,
      },
    ];

    return {
      ...s,
      marketGoods,
      garrison,
      recruitsAvailable,
      lastRecruitRefreshDay: 1,
    };
  });

  // Lay direct natural road paths connecting settlements using Bresenham's algorithm
  const connectWithRoad = (x0: number, y0: number, x1: number, y1: number) => {
    const dx = Math.abs(x1 - x0);
    const dy = Math.abs(y1 - y0);
    const sx = x0 < x1 ? 1 : -1;
    const sy = y0 < y1 ? 1 : -1;
    let err = dx - dy;

    let cx = x0;
    let cy = y0;

    while (true) {
      if (cx >= 0 && cx < width && cy >= 0 && cy < height) {
        if (tiles[cy][cx] !== 'water' && tiles[cy][cx] !== 'mountains') {
          roads[cy][cx] = true;
        }
      }

      if (cx === x1 && cy === y1) break;
      const e2 = 2 * err;
      if (e2 > -dy) {
        err -= dy;
        cx += sx;
      }
      if (e2 < dx) {
        err += dx;
        cy += sy;
      }
    }
  };

  // Connect towns
  const towns = settlements.filter((s) => s.type === 'town');
  for (let i = 0; i < towns.length; i++) {
    const nextTown = towns[(i + 1) % towns.length];
    connectWithRoad(towns[i].x, towns[i].y, nextTown.x, nextTown.y);
  }

  // Connect villages and castles to nearest town
  for (const s of settlements) {
    if (s.type !== 'town') {
      const parent = towns.find((t) => t.id === s.boundTownId) || towns[0];
      connectWithRoad(s.x, s.y, parent.x, parent.y);
    }
  }

  return {
    width,
    height,
    seed,
    tiles,
    roads,
    settlements,
  };
}
