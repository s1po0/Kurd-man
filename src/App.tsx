/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { GameShell } from './ui/GameShell';

export default function App() {
  return (
    <main className="w-screen h-screen overflow-hidden bg-stone-950 font-sans">
      <GameShell />
    </main>
  );
}
