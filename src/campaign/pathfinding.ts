/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { TERRAIN_COSTS } from '../core/constants';
import { MapData, TerrainType, TileCoord } from '../core/types';

interface Node {
  x: number;
  y: number;
  g: number;
  h: number;
  f: number;
  parent?: Node;
}

/**
 * High-performance A* pathfinding on campaign grid.
 */
export function findPath(
  startX: number,
  startY: number,
  targetX: number,
  targetY: number,
  map: MapData,
  maxIterations: number = 3000
): TileCoord[] {
  const sx = Math.floor(startX);
  const sy = Math.floor(startY);
  const tx = Math.floor(targetX);
  const ty = Math.floor(targetY);

  if (sx === tx && sy === ty) {
    return [];
  }

  // Bounds check
  if (
    sx < 0 ||
    sx >= map.width ||
    sy < 0 ||
    sy >= map.height ||
    tx < 0 ||
    tx >= map.width ||
    ty < 0 ||
    ty >= map.height
  ) {
    return [];
  }

  // Target tile impassability check: if target is water or mountain, find closest passable neighbor
  let goalX = tx;
  let goalY = ty;
  if (isImpassable(map.tiles[ty][tx])) {
    const neighbor = findClosestPassableNeighbor(tx, ty, map);
    if (!neighbor) return [];
    goalX = neighbor.x;
    goalY = neighbor.y;
  }

  const openList: Node[] = [];
  const closedSet = new Uint8Array(map.width * map.height);

  const getIdx = (x: number, y: number) => y * map.width + x;

  const heuristic = (x: number, y: number) => {
    // Octile distance for diagonal movement
    const dx = Math.abs(x - goalX);
    const dy = Math.abs(y - goalY);
    return Math.max(dx, dy) + 0.414 * Math.min(dx, dy);
  };

  const startNode: Node = {
    x: sx,
    y: sy,
    g: 0,
    h: heuristic(sx, sy),
    f: heuristic(sx, sy),
  };

  openList.push(startNode);

  // 8-directional neighbor offsets
  const dxs = [0, 1, 0, -1, 1, 1, -1, -1];
  const dys = [-1, 0, 1, 0, -1, 1, 1, -1];

  let iterations = 0;
  let closestNode: Node = startNode;

  while (openList.length > 0 && iterations < maxIterations) {
    iterations++;

    // Find node with lowest f score (linear scan is fast for <= a few hundred open nodes)
    let bestIdx = 0;
    for (let i = 1; i < openList.length; i++) {
      if (openList[i].f < openList[bestIdx].f) {
        bestIdx = i;
      }
    }

    const current = openList.splice(bestIdx, 1)[0];
    closedSet[getIdx(current.x, current.y)] = 1;

    if (current.h < closestNode.h) {
      closestNode = current;
    }

    // Reached destination!
    if (current.x === goalX && current.y === goalY) {
      return reconstructPath(current);
    }

    for (let i = 0; i < 8; i++) {
      const nx = current.x + dxs[i];
      const ny = current.y + dys[i];

      if (nx < 0 || nx >= map.width || ny < 0 || ny >= map.height) {
        continue;
      }

      if (closedSet[getIdx(nx, ny)] === 1) {
        continue;
      }

      const terrain = map.tiles[ny][nx];
      if (isImpassable(terrain)) {
        continue;
      }

      // Base move cost
      const isDiagonal = dxs[i] !== 0 && dys[i] !== 0;
      let cost = isDiagonal ? 1.414 : 1.0;

      // Road discount or terrain multiplier
      if (map.roads[ny][nx]) {
        cost *= TERRAIN_COSTS.road;
      } else {
        cost *= TERRAIN_COSTS[terrain] || 1.0;
      }

      const tentativeG = current.g + cost;

      let existing = openList.find((n) => n.x === nx && n.y === ny);
      if (!existing) {
        const h = heuristic(nx, ny);
        const neighborNode: Node = {
          x: nx,
          y: ny,
          g: tentativeG,
          h,
          f: tentativeG + h,
          parent: current,
        };
        openList.push(neighborNode);
      } else if (tentativeG < existing.g) {
        existing.g = tentativeG;
        existing.f = tentativeG + existing.h;
        existing.parent = current;
      }
    }
  }

  // If exact goal unreachable, return path to closest reachable node
  return reconstructPath(closestNode);
}

function isImpassable(terrain: TerrainType): boolean {
  return terrain === 'mountains' || terrain === 'water';
}

function findClosestPassableNeighbor(x: number, y: number, map: MapData): TileCoord | null {
  for (let radius = 1; radius <= 3; radius++) {
    for (let dy = -radius; dy <= radius; dy++) {
      for (let dx = -radius; dx <= radius; dx++) {
        const nx = x + dx;
        const ny = y + dy;
        if (nx >= 0 && nx < map.width && ny >= 0 && ny < map.height) {
          if (!isImpassable(map.tiles[ny][nx])) {
            return { x: nx, y: ny };
          }
        }
      }
    }
  }
  return null;
}

function reconstructPath(node: Node): TileCoord[] {
  const path: TileCoord[] = [];
  let curr: Node | undefined = node;
  while (curr && curr.parent) {
    path.push({ x: curr.x, y: curr.y });
    curr = curr.parent;
  }
  return path.reverse();
}
