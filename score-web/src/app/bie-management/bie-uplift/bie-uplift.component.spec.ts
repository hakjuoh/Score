import {BieUpliftComponent} from './bie-uplift.component';
import {BieUpliftSourceFlatNode, BieUpliftTargetFlatNode} from './domain/bie-uplift';
import {BieFlatNode, BieFlatNodeDataSource} from '../domain/bie-flat-tree';
import {vi} from 'vitest';

type TestNode = {
  level: number;
  type: string;
  bieType: string;
  parent?: TestNode;
  isGroup: boolean;
  fixed: boolean;
  reuseMapped: boolean;
  used: boolean;
  expandable: boolean;
  queryPath: string;
  source?: TestNode;
  target?: TestNode;
  children: TestNode[];
};

function createNode(level: number, type: string, parent?: TestNode, isGroup = false): TestNode {
  return {
    level,
    type,
    bieType: type,
    parent,
    isGroup,
    fixed: false,
    reuseMapped: false,
    used: true,
    expandable: false,
    queryPath: '/' + type + '-' + level,
    source: undefined,
    target: undefined,
    children: []
  };
}

describe('BieUpliftComponent', () => {
  it('should be defined', () => {
    expect(BieUpliftComponent).toBeTruthy();
  });

  it('does not allow mapping a child when its source parent is unmapped', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ASBIEP');
    const sourceParent = createNode(1, 'ASBIEP', sourceRoot);
    const sourceChild = createNode(2, 'BBIEP', sourceParent);
    const targetRoot = createNode(0, 'ASBIEP');
    const targetParent = createNode(1, 'ASBIEP', targetRoot);
    const targetChild = createNode(2, 'BBIEP', targetParent);

    component.sourceSelectedNode = sourceChild as unknown as BieUpliftSourceFlatNode;

    expect(component.canMatch(targetChild as unknown as BieUpliftTargetFlatNode)).toBe(false);
  });

  it('does not allow mapping a child into an unmapped target parent', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ASBIEP');
    const sourceParent = createNode(1, 'ASBIEP', sourceRoot);
    const sourceChild = createNode(2, 'BBIEP', sourceParent);
    const targetRoot = createNode(0, 'ASBIEP');
    const targetParent = createNode(1, 'ASBIEP', targetRoot);
    const targetChild = createNode(2, 'BBIEP', targetParent);
    sourceParent.target = targetParent;

    component.sourceSelectedNode = sourceChild as unknown as BieUpliftSourceFlatNode;

    expect(component.canMatch(targetChild as unknown as BieUpliftTargetFlatNode)).toBe(false);
  });

  it('allows mapping a child when both structural parents are mapped', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ASBIEP');
    const sourceParent = createNode(1, 'ASBIEP', sourceRoot);
    const sourceChild = createNode(2, 'BBIEP', sourceParent);
    const targetRoot = createNode(0, 'ASBIEP');
    const targetParent = createNode(1, 'ASBIEP', targetRoot);
    const targetChild = createNode(2, 'BBIEP', targetParent);
    sourceParent.target = targetParent;
    targetParent.source = sourceParent;

    component.sourceSelectedNode = sourceChild as unknown as BieUpliftSourceFlatNode;

    expect(component.canMatch(targetChild as unknown as BieUpliftTargetFlatNode)).toBe(true);
  });

  it('clears descendant mappings when a mapped parent is replaced', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ASBIEP');
    const sourceParent = createNode(1, 'ASBIEP', sourceRoot);
    const sourceChild = createNode(2, 'BBIEP', sourceParent);
    const targetRoot = createNode(0, 'ASBIEP');
    const targetParentA = createNode(1, 'ASBIEP', targetRoot);
    const targetChildA = createNode(2, 'BBIEP', targetParentA);
    const targetParentB = createNode(1, 'ASBIEP', targetRoot);
    sourceRoot.target = targetRoot;
    sourceParent.target = targetParentA;
    targetParentA.source = sourceParent;
    sourceChild.target = targetChildA;
    targetChildA.source = sourceChild;
    // Simulate both sides of a collapsed subtree: the mapped child is no longer reachable through
    // either parent's children, but its logical parent references remain available.
    sourceParent.children = [];
    targetParentA.children = [];

    const registerMapping = (component as unknown as {
      registerMapping: (source: TestNode, target: TestNode) => void
    }).registerMapping.bind(component);
    registerMapping(sourceParent, targetParentA);
    registerMapping(sourceChild, targetChildA);

    component.sourceSelectedNode = sourceParent as unknown as BieUpliftSourceFlatNode;
    component.checkMatch({}, targetParentB as unknown as BieUpliftTargetFlatNode);

    expect(sourceChild.target).toBeUndefined();
    expect(targetChildA.source).toBeUndefined();
    expect(targetParentA.source).toBeUndefined();
    expect(sourceParent.target).toBe(targetParentB);
    expect(targetParentB.source).toBe(sourceParent);
  });

  it('validates nested reuse parents from materialized, non-visible candidates', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ASBIEP');
    const sourceReuse = createNode(1, 'ASBIEP', sourceRoot);
    const sourceNestedReuse = createNode(2, 'ASBIEP', sourceReuse);
    const sourceParty = createNode(3, 'BBIEP', sourceNestedReuse);
    const targetRoot = createNode(0, 'ASBIEP');
    const targetReuse = createNode(1, 'ASBIEP', targetRoot);
    const targetNestedReuse = createNode(2, 'ASBIEP', targetReuse);
    const targetParty = createNode(3, 'BBIEP', targetNestedReuse);
    sourceReuse.target = targetReuse;
    targetReuse.source = sourceReuse;
    sourceNestedReuse.target = targetNestedReuse;
    targetNestedReuse.source = sourceNestedReuse;
    component.sourceSelectedNode = sourceParty as unknown as BieUpliftSourceFlatNode;

    const hasMappedParentPair = (component as unknown as {
      hasMappedParentPair: (source: TestNode, target: TestNode, sourceCandidates: TestNode[],
                             targetCandidates: TestNode[]) => boolean
    }).hasMappedParentPair.bind(component);

    expect(hasMappedParentPair(
      sourceParty,
      targetParty,
      [sourceRoot, sourceReuse, sourceNestedReuse, sourceParty],
      [targetRoot, targetReuse, targetNestedReuse, targetParty]
    )).toBe(true);
  });

  it('matches lazily loaded nested reuse descendants after their reuse parent is mapped', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRootRaw = createNode(0, 'ASBIEP');
    sourceRootRaw.queryPath = '/BOM';
    const sourceReuseRaw = createNode(1, 'ASBIEP', sourceRootRaw);
    sourceReuseRaw.queryPath = '/BOM/BOM Item Data';
    sourceReuseRaw.expandable = true;
    const sourcePartyRaw = createNode(2, 'ASBIEP', sourceReuseRaw);
    sourcePartyRaw.queryPath = '/BOM/BOM Item Data/Party';
    const targetRootRaw = createNode(0, 'ASBIEP');
    targetRootRaw.queryPath = '/BOM';
    const targetReuseRaw = createNode(1, 'ASBIEP', targetRootRaw);
    targetReuseRaw.queryPath = '/BOM/BOM Item Data';
    targetReuseRaw.expandable = true;
    const targetPartyRaw = createNode(2, 'ASBIEP', targetReuseRaw);
    targetPartyRaw.queryPath = '/BOM/BOM Item Data/Party';

    const sourceRoot = new BieUpliftSourceFlatNode(sourceRootRaw as unknown as BieFlatNode);
    const sourceReuse = new BieUpliftSourceFlatNode(sourceReuseRaw as unknown as BieFlatNode);
    const sourceParty = new BieUpliftSourceFlatNode(sourcePartyRaw as unknown as BieFlatNode);
    const targetRoot = new BieUpliftTargetFlatNode(targetRootRaw as unknown as BieFlatNode);
    const targetReuse = new BieUpliftTargetFlatNode(targetReuseRaw as unknown as BieFlatNode);
    const targetParty = new BieUpliftTargetFlatNode(targetPartyRaw as unknown as BieFlatNode);
    sourceRootRaw.children = [sourceReuse as unknown as TestNode];
    targetRootRaw.children = [targetReuse as unknown as TestNode];
    sourceRoot.target = targetRoot;
    targetRoot.source = sourceRoot;

    const sourceDatabase = {
      loadChildren: vi.fn((node: BieUpliftSourceFlatNode) => {
        if (node === sourceReuse) {
          node.children = [sourceParty];
        }
      })
    };
    const targetDatabase = {
      loadChildren: vi.fn((node: BieUpliftTargetFlatNode) => {
        if (node === targetReuse) {
          node.children = [targetParty];
        }
      })
    };
    component.sourceDataSource = {data: [sourceRoot], database: sourceDatabase} as unknown as
      BieFlatNodeDataSource<BieUpliftSourceFlatNode>;
    component.targetDataSource = {data: [targetRoot], database: targetDatabase} as unknown as
      BieFlatNodeDataSource<BieUpliftTargetFlatNode>;

    const matchReuseDescendants = (component as unknown as {
      matchReuseDescendants: (source: BieUpliftSourceFlatNode, target: BieUpliftTargetFlatNode) => void
    }).matchReuseDescendants.bind(component);
    matchReuseDescendants(sourceRoot, targetRoot);

    expect(sourceDatabase.loadChildren).toHaveBeenCalledWith(sourceReuse);
    expect(targetDatabase.loadChildren).toHaveBeenCalledWith(targetReuse);
    expect(sourceReuse.target).toBe(targetReuse);
    expect(targetReuse.source).toBe(sourceReuse);
    expect(sourceParty.target).toBe(targetParty);
    expect(targetParty.source).toBe(sourceParty);
    expect(targetParty.reuseMapped).toBe(true);
  });

  it('ignores group containers while checking structural parents', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ASBIEP');
    const sourceParent = createNode(1, 'ASBIEP', sourceRoot);
    const sourceGroup = createNode(2, 'ASBIEP', sourceParent, true);
    const sourceChild = createNode(3, 'BBIEP', sourceGroup);
    const targetRoot = createNode(0, 'ASBIEP');
    const targetParent = createNode(1, 'ASBIEP', targetRoot);
    const targetGroup = createNode(2, 'ASBIEP', targetParent, true);
    const targetChild = createNode(3, 'BBIEP', targetGroup);
    sourceParent.target = targetParent;
    targetParent.source = sourceParent;

    component.sourceSelectedNode = sourceChild as unknown as BieUpliftSourceFlatNode;

    expect(component.canMatch(targetChild as unknown as BieUpliftTargetFlatNode)).toBe(true);
  });

  it('resolves mapped parents through uplift wrapper nodes', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const rawSourceRoot = createNode(0, 'ASBIEP');
    const rawSourceParent = createNode(1, 'ASBIEP', rawSourceRoot);
    const rawSourceChild = createNode(2, 'BBIEP', rawSourceParent);
    const rawTargetRoot = createNode(0, 'ASBIEP');
    const rawTargetParent = createNode(1, 'ASBIEP', rawTargetRoot);
    const rawTargetChild = createNode(2, 'BBIEP', rawTargetParent);
    const sourceRoot = new BieUpliftSourceFlatNode(rawSourceRoot as unknown as BieFlatNode);
    const sourceParent = new BieUpliftSourceFlatNode(rawSourceParent as unknown as BieFlatNode);
    const sourceChild = new BieUpliftSourceFlatNode(rawSourceChild as unknown as BieFlatNode);
    const targetRoot = new BieUpliftTargetFlatNode(rawTargetRoot as unknown as BieFlatNode);
    const targetParent = new BieUpliftTargetFlatNode(rawTargetParent as unknown as BieFlatNode);
    const targetChild = new BieUpliftTargetFlatNode(rawTargetChild as unknown as BieFlatNode);
    sourceParent.target = targetParent;
    targetParent.source = sourceParent;

    component.sourceDataSource = {data: [sourceRoot, sourceParent, sourceChild]} as unknown as
      BieFlatNodeDataSource<BieUpliftSourceFlatNode>;
    component.targetDataSource = {data: [targetRoot, targetParent, targetChild]} as unknown as
      BieFlatNodeDataSource<BieUpliftTargetFlatNode>;
    component.sourceSelectedNode = sourceChild;

    expect(component.canMatch(targetChild)).toBe(true);
  });
});
