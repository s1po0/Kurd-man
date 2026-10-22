/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { MAP_CONFIG, TERRAIN_COLORS } from '../core/constants';
import { CampaignTime, MapData, PartyData, SettlementData, TileCoord } from '../core/types';
import { FACTIONS } from '../data/factions';

export interface Camera {
  x: number; // World tile x
  y: number; // World tile y
  zoom: number;
}

export class CampaignMapRenderer {
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private dpr: number = 1;

  constructor(canvas: HTMLCanvasElement) {
    this.canvas = canvas;
    const context = canvas.getContext('2d', { alpha: false });
    if (!context) throw new Error('Could not acquire 2D canvas context');
    this.ctx = context;
    this.updateDpr();
  }

  updateDpr(): void {
    this.dpr = Math.min(window.devicePixelRatio || 1, 2);
  }

  resize(width: number, height: number): void {
    this.updateDpr();
    this.canvas.width = Math.floor(width * this.dpr);
    this.canvas.height = Math.floor(height * this.dpr);
    this.canvas.style.width = `${width}px`;
    this.canvas.style.height = `${height}px`;
  }

  render(
    map: MapData,
    playerParty: PartyData,
    aiParties: PartyData[],
    camera: Camera,
    time: CampaignTime,
    exploredTiles: boolean[][],
    fogOfWarEnabled: boolean = true,
    destinationTap?: TileCoord | null
  ): void {
    const ctx = this.ctx;
    const w = this.canvas.width;
    const h = this.canvas.height;
    const tileSize = MAP_CONFIG.tileSize * camera.zoom * this.dpr;

    ctx.save();
    ctx.scale(this.dpr, this.dpr);

    const viewW = w / this.dpr;
    const viewH = h / this.dpr;

    // Clear background with rich antique parchment/ocean tone
    ctx.fillStyle = '#111a16';
    ctx.fillRect(0, 0, viewW, viewH);

    // Camera offset: center player or camera target
    const screenCenterX = viewW / 2;
    const screenCenterY = viewH / 2;

    const toScreenX = (tileX: number) => screenCenterX + (tileX - camera.x) * (MAP_CONFIG.tileSize * camera.zoom);
    const toScreenY = (tileY: number) => screenCenterY + (tileY - camera.y) * (MAP_CONFIG.tileSize * camera.zoom);

    // Viewport Culling Bounds
    const minTileX = Math.max(0, Math.floor(camera.x - (screenCenterX / (MAP_CONFIG.tileSize * camera.zoom)) - 2));
    const maxTileX = Math.min(map.width - 1, Math.ceil(camera.x + (screenCenterX / (MAP_CONFIG.tileSize * camera.zoom)) + 2));
    const minTileY = Math.max(0, Math.floor(camera.y - (screenCenterY / (MAP_CONFIG.tileSize * camera.zoom)) - 2));
    const maxTileY = Math.min(map.height - 1, Math.ceil(camera.y + (screenCenterY / (MAP_CONFIG.tileSize * camera.zoom)) + 2));

    const effectiveTilePx = MAP_CONFIG.tileSize * camera.zoom;

    // 1. Draw Terrain Tiles
    for (let y = minTileY; y <= maxTileY; y++) {
      for (let x = minTileX; x <= maxTileX; x++) {
        const isExplored = !fogOfWarEnabled || exploredTiles[y]?.[x];
        const sx = toScreenX(x);
        const sy = toScreenY(y);

        const terrain = map.tiles[y][x];
        const colors = TERRAIN_COLORS[terrain] || TERRAIN_COLORS.plains;

        // Base tile fill
        ctx.fillStyle = isExplored ? colors.base : '#141d18';
        ctx.fillRect(sx, sy, effectiveTilePx + 0.5, effectiveTilePx + 0.5);

        if (isExplored) {
          // Procedural vector details inside tile
          if (effectiveTilePx >= 20) {
            this.renderTerrainDetail(ctx, terrain, sx, sy, effectiveTilePx, x, y, colors);
          }
        }
      }
    }

    // 1b. Render Roads as continuous connecting strokes
    ctx.strokeStyle = '#6f593e';
    ctx.lineWidth = Math.max(2.5, effectiveTilePx * 0.18);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    for (let y = minTileY; y <= maxTileY; y++) {
      for (let x = minTileX; x <= maxTileX; x++) {
        if (!map.roads[y]?.[x]) continue;
        if (fogOfWarEnabled && !exploredTiles[y]?.[x]) continue;

        const cx = toScreenX(x) + effectiveTilePx * 0.5;
        const cy = toScreenY(y) + effectiveTilePx * 0.5;

        // Connect to right neighbor if road
        if (x + 1 < map.width && map.roads[y]?.[x + 1]) {
          ctx.beginPath();
          ctx.moveTo(cx, cy);
          ctx.lineTo(toScreenX(x + 1) + effectiveTilePx * 0.5, cy);
          ctx.stroke();
        }

        // Connect to bottom neighbor if road
        if (y + 1 < map.height && map.roads[y + 1]?.[x]) {
          ctx.beginPath();
          ctx.moveTo(cx, cy);
          ctx.lineTo(cx, toScreenY(y + 1) + effectiveTilePx * 0.5);
          ctx.stroke();
        }
      }
    }

    // 2. Draw Player Path Trajectory Line
    if (playerParty.path.length > 0) {
      ctx.strokeStyle = '#f59e0b';
      ctx.lineWidth = 2.5;
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(toScreenX(playerParty.x), toScreenY(playerParty.y));
      for (const pt of playerParty.path) {
        ctx.lineTo(toScreenX(pt.x), toScreenY(pt.y));
      }
      ctx.stroke();
      ctx.setLineDash([]);
    }

    // 3. Draw Destination Tap Ring
    if (destinationTap) {
      const destSx = toScreenX(destinationTap.x);
      const destSy = toScreenY(destinationTap.y);
      const now = performance.now() * 0.004;
      const pulseRadius = (12 + Math.sin(now) * 4) * camera.zoom;

      ctx.strokeStyle = '#fbbf24';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(destSx, destSy, pulseRadius, 0, Math.PI * 2);
      ctx.stroke();

      ctx.fillStyle = 'rgba(251, 191, 36, 0.25)';
      ctx.beginPath();
      ctx.arc(destSx, destSy, pulseRadius * 0.7, 0, Math.PI * 2);
      ctx.fill();
    }

    // 4. Draw Settlements
    for (const s of map.settlements) {
      const isExplored = !fogOfWarEnabled || exploredTiles[s.y]?.[s.x];
      if (!isExplored) continue;

      const sx = toScreenX(s.x);
      const sy = toScreenY(s.y);

      // Check within screen bounds
      if (sx < -40 || sx > viewW + 40 || sy < -40 || sy > viewH + 40) continue;

      this.renderSettlement(ctx, s, sx, sy, effectiveTilePx);
    }

    // 5. Draw Autonomous AI Parties (only if explored or close)
    for (const p of aiParties) {
      const tileX = Math.floor(p.x);
      const tileY = Math.floor(p.y);
      const isExplored = !fogOfWarEnabled || exploredTiles[tileY]?.[tileX];

      // Proximity vision check to player
      const distToPlayer = Math.hypot(p.x - playerParty.x, p.y - playerParty.y);
      if (!isExplored && distToPlayer > MAP_CONFIG.visionRadius) continue;

      const sx = toScreenX(p.x);
      const sy = toScreenY(p.y);

      if (sx < -30 || sx > viewW + 30 || sy < -30 || sy > viewH + 30) continue;

      this.renderParty(ctx, p, sx, sy, effectiveTilePx, false);
    }

    // 6. Draw Player Party
    this.renderParty(ctx, playerParty, toScreenX(playerParty.x), toScreenY(playerParty.y), effectiveTilePx, true);

    // 7. Day / Night Ambient Lighting Overlay
    this.renderDayNightLighting(ctx, viewW, viewH, time, toScreenX, toScreenY, playerParty, map.settlements);

    ctx.restore();
  }

  /**
   * Render procedural tile internal textures (trees, peaks, dunes, waves).
   */
  private renderTerrainDetail(
    ctx: CanvasRenderingContext2D,
    terrain: string,
    sx: number,
    sy: number,
    size: number,
    x: number,
    y: number,
    colors: { base: string; accent: string; detail: string }
  ): void {
    const pseudoSeed = (x * 73 + y * 97) % 100;

    if (terrain === 'forest') {
      // 3 clustered leafy tree circles with dark trunks
      ctx.fillStyle = '#1c150c';
      ctx.fillRect(sx + size * 0.46, sy + size * 0.7, size * 0.08, size * 0.2);

      ctx.fillStyle = colors.accent;
      ctx.beginPath();
      ctx.arc(sx + size * 0.35, sy + size * 0.42, size * 0.28, 0, Math.PI * 2);
      ctx.arc(sx + size * 0.65, sy + size * 0.45, size * 0.26, 0, Math.PI * 2);
      ctx.arc(sx + size * 0.5, sy + size * 0.65, size * 0.26, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = colors.detail;
      ctx.beginPath();
      ctx.arc(sx + size * 0.35, sy + size * 0.38, size * 0.16, 0, Math.PI * 2);
      ctx.arc(sx + size * 0.65, sy + size * 0.42, size * 0.15, 0, Math.PI * 2);
      ctx.fill();
    } else if (terrain === 'mountains') {
      // Jagged mountain triangle with snowy peak and ridge shadow
      ctx.fillStyle = '#3a393c';
      ctx.beginPath();
      ctx.moveTo(sx + size * 0.1, sy + size * 0.9);
      ctx.lineTo(sx + size * 0.5, sy + size * 0.12);
      ctx.lineTo(sx + size * 0.5, sy + size * 0.9);
      ctx.closePath();
      ctx.fill();

      ctx.fillStyle = colors.accent;
      ctx.beginPath();
      ctx.moveTo(sx + size * 0.5, sy + size * 0.12);
      ctx.lineTo(sx + size * 0.9, sy + size * 0.9);
      ctx.lineTo(sx + size * 0.5, sy + size * 0.9);
      ctx.closePath();
      ctx.fill();

      // Snow peak
      ctx.fillStyle = '#f8fafc';
      ctx.beginPath();
      ctx.moveTo(sx + size * 0.38, sy + size * 0.38);
      ctx.lineTo(sx + size * 0.5, sy + size * 0.12);
      ctx.lineTo(sx + size * 0.62, sy + size * 0.38);
      ctx.closePath();
      ctx.fill();
    } else if (terrain === 'hills') {
      // Topographic crag mounds
      ctx.fillStyle = colors.accent;
      ctx.beginPath();
      ctx.arc(sx + size * 0.4, sy + size * 0.8, size * 0.35, Math.PI, Math.PI * 2);
      ctx.arc(sx + size * 0.7, sy + size * 0.85, size * 0.3, Math.PI, Math.PI * 2);
      ctx.fill();
    } else if (terrain === 'snow') {
      // Frosted winter taiga pine trees
      ctx.fillStyle = '#1e382d';
      ctx.beginPath();
      ctx.moveTo(sx + size * 0.5, sy + size * 0.2);
      ctx.lineTo(sx + size * 0.3, sy + size * 0.75);
      ctx.lineTo(sx + size * 0.7, sy + size * 0.75);
      ctx.closePath();
      ctx.fill();

      ctx.fillStyle = '#f1f5f9';
      ctx.fillRect(sx + size * 0.42, sy + size * 0.35, size * 0.16, size * 0.08);
    } else if (terrain === 'desert') {
      // Golden dune curve
      ctx.strokeStyle = colors.accent;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(sx + size * 0.1, sy + size * 0.65);
      ctx.quadraticCurveTo(sx + size * 0.5, sy + size * 0.4, sx + size * 0.9, sy + size * 0.7);
      ctx.stroke();
    } else if (terrain === 'plains') {
      // Subtle grass blade tufts
      ctx.strokeStyle = colors.accent;
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(sx + size * 0.45, sy + size * 0.6);
      ctx.lineTo(sx + size * 0.42, sy + size * 0.45);
      ctx.moveTo(sx + size * 0.5, sy + size * 0.6);
      ctx.lineTo(sx + size * 0.54, sy + size * 0.42);
      ctx.stroke();
    } else if (terrain === 'water') {
      // Coastal ripple waves
      ctx.strokeStyle = colors.detail;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.moveTo(sx + size * 0.2, sy + size * 0.45);
      ctx.quadraticCurveTo(sx + size * 0.4, sy + size * 0.4, sx + size * 0.6, sy + size * 0.45);
      ctx.moveTo(sx + size * 0.4, sy + size * 0.7);
      ctx.quadraticCurveTo(sx + size * 0.6, sy + size * 0.65, sx + size * 0.8, sy + size * 0.7);
      ctx.stroke();
    }
  }

  /**
   * Render settlement icon (Town, Castle, Village).
   */
  private renderSettlement(
    ctx: CanvasRenderingContext2D,
    s: SettlementData,
    sx: number,
    sy: number,
    tileSize: number
  ): void {
    const faction = FACTIONS[s.factionId] || FACTIONS.vaeloria;
    const iconSize = Math.max(16, tileSize * 0.7);

    ctx.save();
    ctx.translate(sx + tileSize * 0.5, sy + tileSize * 0.5);

    if (s.type === 'town') {
      // Town: Walled castle citadel with towers
      ctx.fillStyle = '#1e293b';
      ctx.beginPath();
      ctx.rect(-iconSize * 0.6, -iconSize * 0.5, iconSize * 1.2, iconSize);
      ctx.fill();

      // Battlements
      ctx.fillStyle = '#334155';
      ctx.fillRect(-iconSize * 0.6, -iconSize * 0.6, iconSize * 0.3, iconSize * 0.3);
      ctx.fillRect(iconSize * 0.3, -iconSize * 0.6, iconSize * 0.3, iconSize * 0.3);

      // Central Spire
      ctx.fillStyle = faction.primaryColor;
      ctx.beginPath();
      ctx.moveTo(-iconSize * 0.25, -iconSize * 0.4);
      ctx.lineTo(0, -iconSize * 0.85);
      ctx.lineTo(iconSize * 0.25, -iconSize * 0.4);
      ctx.closePath();
      ctx.fill();

      // Gate arch
      ctx.fillStyle = '#0f172a';
      ctx.fillRect(-iconSize * 0.15, iconSize * 0.1, iconSize * 0.3, iconSize * 0.4);
    } else if (s.type === 'castle') {
      // Castle: Sturdy single keep with banner
      ctx.fillStyle = '#334155';
      ctx.fillRect(-iconSize * 0.45, -iconSize * 0.45, iconSize * 0.9, iconSize * 0.9);

      ctx.fillStyle = faction.primaryColor;
      ctx.fillRect(-iconSize * 0.1, -iconSize * 0.75, iconSize * 0.2, iconSize * 0.35);
    } else {
      // Village: Thatched farm cottage
      ctx.fillStyle = '#78350f';
      ctx.fillRect(-iconSize * 0.35, -iconSize * 0.1, iconSize * 0.7, iconSize * 0.5);

      // Thatched roof
      ctx.fillStyle = '#b45309';
      ctx.beginPath();
      ctx.moveTo(-iconSize * 0.45, -iconSize * 0.1);
      ctx.lineTo(0, -iconSize * 0.5);
      ctx.lineTo(iconSize * 0.45, -iconSize * 0.1);
      ctx.closePath();
      ctx.fill();
    }

    // Name Banner Label
    ctx.font = 'bold 11px var(--font-sans)';
    ctx.textAlign = 'center';

    const textMetrics = ctx.measureText(s.name);
    const labelW = textMetrics.width + 10;
    const labelH = 16;

    ctx.fillStyle = 'rgba(15, 23, 42, 0.85)';
    ctx.fillRect(-labelW / 2, iconSize * 0.65, labelW, labelH);

    ctx.fillStyle = s.type === 'town' ? '#f59e0b' : '#e2e8f0';
    ctx.fillText(s.name, 0, iconSize * 0.65 + 12);

    ctx.restore();
  }

  /**
   * Render party token (Player hero, lord army, caravan, bandit).
   */
  private renderParty(
    ctx: CanvasRenderingContext2D,
    p: PartyData,
    sx: number,
    sy: number,
    tileSize: number,
    isPlayer: boolean
  ): void {
    const size = Math.max(14, tileSize * 0.55);

    ctx.save();
    ctx.translate(sx + tileSize * 0.5, sy + tileSize * 0.5);

    // Party Base Token Circle
    ctx.beginPath();
    ctx.arc(0, 0, size * 0.5, 0, Math.PI * 2);

    if (isPlayer) {
      // Player: Bright Gold Ring & Heraldic Blue
      ctx.fillStyle = '#1e3a8a';
      ctx.fill();
      ctx.strokeStyle = '#f59e0b';
      ctx.lineWidth = 3;
      ctx.stroke();

      // Crown / Star emblem
      ctx.fillStyle = '#fbbf24';
      ctx.beginPath();
      ctx.arc(0, -1, size * 0.22, 0, Math.PI * 2);
      ctx.fill();
    } else if (p.partyType === 'bandit') {
      // Bandits: Crimson & Black
      ctx.fillStyle = '#7f1d1d';
      ctx.fill();
      ctx.strokeStyle = '#ef4444';
      ctx.lineWidth = 2;
      ctx.stroke();

      // Crossbones / Skull mark
      ctx.fillStyle = '#fca5a5';
      ctx.fillRect(-size * 0.18, -size * 0.08, size * 0.36, size * 0.16);
    } else if (p.partyType === 'caravan') {
      // Caravan: Merchant Bronze
      ctx.fillStyle = '#92400e';
      ctx.fill();
      ctx.strokeStyle = '#fbbf24';
      ctx.lineWidth = 2;
      ctx.stroke();
    } else {
      // Lord Army: Faction heraldic primary
      const faction = FACTIONS[p.factionId as keyof typeof FACTIONS] || FACTIONS.vaeloria;
      ctx.fillStyle = faction.primaryColor;
      ctx.fill();
      ctx.strokeStyle = faction.secondaryColor;
      ctx.lineWidth = 2.5;
      ctx.stroke();
    }

    // Troop count pill badge
    const troopCount = p.troops.reduce((sum, t) => sum + t.count, 0);
    ctx.font = 'bold 9px var(--font-sans)';
    ctx.textAlign = 'center';

    const countText = `${troopCount}`;
    const badgeW = Math.max(16, ctx.measureText(countText).width + 6);

    ctx.fillStyle = '#0f172a';
    ctx.fillRect(-badgeW / 2, size * 0.55, badgeW, 12);
    ctx.fillStyle = '#f8fafc';
    ctx.fillText(countText, 0, size * 0.55 + 9.5);

    ctx.restore();
  }

  /**
   * Render Day/Night cycle lighting tint + warm torches.
   */
  private renderDayNightLighting(
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    time: CampaignTime,
    toScreenX: (x: number) => number,
    toScreenY: (y: number) => number,
    playerParty: PartyData,
    settlements: SettlementData[]
  ): void {
    const hour = time.hour + time.minute / 60;

    // Darkness factor: 0.0 (midday) to 0.75 (midnight)
    let darkness = 0.0;
    let r = 10, g = 15, b = 45; // Dark indigo night tone

    if (hour >= 21 || hour < 4.5) {
      darkness = 0.72; // Full Night
    } else if (hour >= 4.5 && hour < 6.5) {
      // Dawn
      const progress = (hour - 4.5) / 2.0;
      darkness = 0.72 * (1 - progress);
      r = 60; g = 30; b = 45;
    } else if (hour >= 18.5 && hour < 21) {
      // Dusk
      const progress = (hour - 18.5) / 2.5;
      darkness = 0.72 * progress;
      r = 70; g = 35; b = 30;
    }

    if (darkness <= 0.05) {
      return; // Clear day
    }

    ctx.save();
    // Ambient darkness layer
    ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${darkness})`;
    ctx.fillRect(0, 0, w, h);

    // Warm radial torchlight around player
    const px = toScreenX(playerParty.x) + MAP_CONFIG.tileSize * 0.5;
    const py = toScreenY(playerParty.y) + MAP_CONFIG.tileSize * 0.5;

    const torchGradient = ctx.createRadialGradient(px, py, 15, px, py, 130);
    torchGradient.addColorStop(0, 'rgba(254, 240, 138, 0.45)');
    torchGradient.addColorStop(0.5, 'rgba(245, 158, 11, 0.2)');
    torchGradient.addColorStop(1, 'rgba(0, 0, 0, 0)');

    ctx.globalCompositeOperation = 'destination-out';
    ctx.fillStyle = torchGradient;
    ctx.beginPath();
    ctx.arc(px, py, 130, 0, Math.PI * 2);
    ctx.fill();

    // Warm lantern glow on visible towns
    for (const s of settlements) {
      const sx = toScreenX(s.x) + MAP_CONFIG.tileSize * 0.5;
      const sy = toScreenY(s.y) + MAP_CONFIG.tileSize * 0.5;

      if (sx >= -100 && sx <= w + 100 && sy >= -100 && sy <= h + 100) {
        const townGlow = ctx.createRadialGradient(sx, sy, 10, sx, sy, s.type === 'town' ? 120 : 70);
        townGlow.addColorStop(0, 'rgba(251, 191, 36, 0.4)');
        townGlow.addColorStop(1, 'rgba(0, 0, 0, 0)');
        ctx.fillStyle = townGlow;
        ctx.beginPath();
        ctx.arc(sx, sy, s.type === 'town' ? 120 : 70, 0, Math.PI * 2);
        ctx.fill();
      }
    }

    ctx.restore();
  }
}
