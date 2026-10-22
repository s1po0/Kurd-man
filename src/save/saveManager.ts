/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { SaveGameMetadata, SaveGameState } from '../core/types';

const DB_NAME = 'IronAndCrown_SavesDB';
const STORE_NAME = 'save_slots';
const DB_VERSION = 1;
const LOCAL_STORAGE_PREFIX = 'iron_and_crown_save_';

/**
 * Open or upgrade IndexedDB database.
 */
function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined' || !window.indexedDB) {
      reject(new Error('IndexedDB not available'));
      return;
    }

    const request = window.indexedDB.open(DB_NAME, DB_VERSION);

    request.onerror = () => {
      reject(request.error);
    };

    request.onsuccess = () => {
      resolve(request.result);
    };

    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'metadata.slot' });
      }
    };
  });
}

/**
 * Migrate save game schema across versions if necessary.
 */
function migrateSaveState(raw: unknown): SaveGameState {
  const save = raw as SaveGameState;
  if (!save.version || save.version === 1) {
    // Current base version
    save.version = 1;
  }
  return save;
}

export class SaveManager {
  /**
   * Save a game state into a specified slot (0 = autosave, 1, 2, 3 = manual).
   */
  static async save(slot: number, state: SaveGameState): Promise<boolean> {
    try {
      state.metadata.slot = slot;
      state.metadata.timestamp = Date.now();

      // Try IndexedDB first
      const db = await openDatabase();
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        const req = store.put(state);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
      return true;
    } catch {
      // Fallback to localStorage
      try {
        localStorage.setItem(`${LOCAL_STORAGE_PREFIX}${slot}`, JSON.stringify(state));
        return true;
      } catch {
        return false;
      }
    }
  }

  /**
   * Load a game state from a slot.
   */
  static async load(slot: number): Promise<SaveGameState | null> {
    try {
      const db = await openDatabase();
      const raw = await new Promise<SaveGameState | undefined>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const req = store.get(slot);
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      });

      if (raw) {
        return migrateSaveState(raw);
      }
    } catch {
      // Try localStorage fallback
    }

    try {
      const item = localStorage.getItem(`${LOCAL_STORAGE_PREFIX}${slot}`);
      if (item) {
        const parsed = JSON.parse(item);
        return migrateSaveState(parsed);
      }
    } catch {
      // Ignore
    }

    return null;
  }

  /**
   * List metadata for all save slots.
   */
  static async listSaves(): Promise<Record<number, SaveGameMetadata | null>> {
    const result: Record<number, SaveGameMetadata | null> = {
      0: null,
      1: null,
      2: null,
      3: null,
    };

    for (let slot = 0; slot <= 3; slot++) {
      try {
        const save = await this.load(slot);
        if (save) {
          result[slot] = save.metadata;
        }
      } catch {
        result[slot] = null;
      }
    }

    return result;
  }

  /**
   * Delete a save slot.
   */
  static async delete(slot: number): Promise<boolean> {
    try {
      const db = await openDatabase();
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        const req = store.delete(slot);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
    } catch {
      // Ignore
    }

    try {
      localStorage.removeItem(`${LOCAL_STORAGE_PREFIX}${slot}`);
    } catch {
      // Ignore
    }

    return true;
  }
}
