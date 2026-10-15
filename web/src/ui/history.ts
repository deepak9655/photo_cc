/**
 * Undo/redo.
 *
 * Deliberately NOT snapshot-based: a phone cannot afford to keep N copies of a
 * 40 MB document. Two kinds of entries are stored instead:
 *
 *   - `patch`: the pixel tiles a stroke overwrote (a few tens of KB), replayed
 *     backwards to undo.
 *   - `state`: the position of one layer inside the tree (`before` / `after`),
 *     used for reorder / delete / duplicate / add / rename / opacity changes.
 *
 * Anything more structural (a whole new layer) is stored as the detached layer
 * object itself, which is shared, not copied — so deleting a layer costs nothing
 * until it is undone.
 */

import { findPath, insertLayer, removeLayer, restorePatch, type PixelPatch } from '../core/ops';
import type { Doc, Layer } from '../core/types';

interface PatchEntry {
  kind: 'patch';
  label: string;
  patch: PixelPatch;
}

interface StateEntry {
  kind: 'state';
  label: string;
  before: DocState;
  after: DocState;
}

interface LayerEntry {
  kind: 'layer';
  label: string;
  /** add: layer was created, so undo removes it; remove: reverse. */
  mode: 'add' | 'remove';
  layer: Layer;
  parentId: string | null;
  index: number;
}

interface PropsEntry {
  kind: 'props';
  label: string;
  layerId: string;
  before: Record<string, unknown>;
  after: Record<string, unknown>;
}

type Entry = PatchEntry | StateEntry | LayerEntry | PropsEntry;

interface DocState {
  parentId: string | null;
  index: number;
  layer: Layer;
}

const MAX_ENTRIES = 40;

export class History {
  private undoStack: Entry[] = [];
  private redoStack: Entry[] = [];
  constructor(private doc: () => Doc, private onChange: () => void) {}

  get canUndo(): boolean { return this.undoStack.length > 0; }
  get canRedo(): boolean { return this.redoStack.length > 0; }
  get undoLabel(): string { return this.undoStack.at(-1)?.label ?? ''; }
  get redoLabel(): string { return this.redoStack.at(-1)?.label ?? ''; }

  private push(entry: Entry): void {
    this.undoStack.push(entry);
    if (this.undoStack.length > MAX_ENTRIES) this.undoStack.shift();
    this.redoStack.length = 0;
    this.onChange();
  }

  /* -------------------------------------------------------- entry creators */

  pushPixelPatch(label: string, patch: PixelPatch | null): void {
    if (!patch) return;
    this.push({ kind: 'patch', label, patch });
  }

  /** Wraps a structural change: call with a closure that performs it. */
  runState(label: string, layerId: string, mutate: () => void): void {
    const doc = this.doc();
    const before = this.snapshot(doc, layerId);
    mutate();
    const after = this.snapshot(doc, layerId);
    if (!before || !after) { this.onChange(); return; }
    this.push({ kind: 'state', label, before, after });
  }

  pushLayerAdd(label: string, layer: Layer, parentId: string | null, index: number): void {
    this.push({ kind: 'layer', label, mode: 'add', layer, parentId, index });
  }

  pushLayerRemove(label: string, layer: Layer, parentId: string | null, index: number): void {
    this.push({ kind: 'layer', label, mode: 'remove', layer, parentId, index });
  }

  pushProps(label: string, layer: Layer, before: Record<string, unknown>, after: Record<string, unknown>): void {
    this.push({ kind: 'props', label, layerId: layer.id, before, after });
  }

  private snapshot(doc: Doc, id: string): DocState | null {
    const path = findPath(doc, id);
    if (!path) return null;
    return { parentId: path.parent?.id ?? null, index: path.index, layer: path.list[path.index] };
  }

  private applyState(doc: Doc, state: DocState): void {
    removeLayer(doc, state.layer.id);
    insertLayer(doc, state.layer, state.parentId, state.index);
  }

  /* ------------------------------------------------------------- undo/redo */

  undo(): boolean {
    const entry = this.undoStack.pop();
    if (!entry) return false;
    const doc = this.doc();
    switch (entry.kind) {
      case 'patch':
        restorePatch(doc, entry.patch, 'before');
        break;
      case 'state':
        this.applyState(doc, entry.before);
        break;
      case 'layer':
        if (entry.mode === 'add') {
          const index = findPath(doc, entry.layer.id)?.index ?? entry.index;
          removeLayer(doc, entry.layer.id);
          entry.index = index;
        } else {
          insertLayer(doc, entry.layer, entry.parentId, entry.index);
        }
        break;
      case 'props': {
        const layer = findLayerRef(doc, entry.layerId);
        if (layer) Object.assign(layer, entry.before);
        break;
      }
    }
    this.redoStack.push(entry);
    this.onChange();
    return true;
  }

  redo(): boolean {
    const entry = this.redoStack.pop();
    if (!entry) return false;
    const doc = this.doc();
    switch (entry.kind) {
      case 'patch':
        restorePatch(doc, entry.patch, 'after');
        break;
      case 'state':
        this.applyState(doc, entry.after);
        break;
      case 'layer':
        if (entry.mode === 'add') insertLayer(doc, entry.layer, entry.parentId, entry.index);
        else removeLayer(doc, entry.layer.id);
        break;
      case 'props': {
        const layer = findLayerRef(doc, entry.layerId);
        if (layer) Object.assign(layer, entry.after);
        break;
      }
    }
    this.undoStack.push(entry);
    this.onChange();
    return true;
  }

  clear(): void { this.undoStack.length = 0; this.redoStack.length = 0; this.onChange(); }
}

function findLayerRef(doc: Doc, id: string): Layer | null {
  const path = findPath(doc, id);
  return path ? path.list[path.index] : null;
}

