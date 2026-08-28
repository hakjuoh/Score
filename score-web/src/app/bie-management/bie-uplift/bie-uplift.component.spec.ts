import {BieUpliftComponent} from './bie-uplift.component';
import {BieUpliftSourceFlatNode, BieUpliftTargetFlatNode} from './domain/bie-uplift';
import {BieFlatNode, BieFlatNodeDataSource} from '../domain/bie-flat-tree';
import {BieEditAbieNode} from '../bie-edit/domain/bie-edit-node';
import {of} from 'rxjs';
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
  reused?: boolean;
  reusedTopLevelAsbiepId?: number;
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
    reused: false,
    reusedTopLevelAsbiepId: undefined,
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

  it('keeps a manual mapping when selecting a reuse BIE is cancelled', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceReuse = createNode(1, 'ASBIEP');
    sourceReuse.reused = true;
    const sourceChild = createNode(2, 'BBIEP', sourceReuse);
    const targetReuse = createNode(1, 'ASBIEP');
    const targetChild = createNode(2, 'BBIEP', targetReuse);
    (targetReuse as any)._node = {asccpNode: {manifestId: 1}};
    sourceReuse.target = targetReuse;
    targetReuse.source = sourceReuse;
    sourceChild.target = targetChild;
    targetChild.source = sourceChild;

    (component as any).dialog = {open: vi.fn().mockReturnValue({afterClosed: () => of(undefined)})};
    (component as any).hasMappedParentPair = vi.fn(() => true);

    component.matchReused(targetReuse as unknown as BieUpliftTargetFlatNode);

    expect(sourceChild.target).toBe(targetChild);
    expect(targetChild.source).toBe(sourceChild);
    expect(targetReuse.reusedTopLevelAsbiepId).toBeUndefined();
  });

  it('applies a selected reuse BIE only after its data has loaded', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = new BieUpliftSourceFlatNode(
      createNode(0, 'ASBIEP') as unknown as BieFlatNode);
    const sourceReuse = new BieUpliftSourceFlatNode(
      createNode(1, 'ASBIEP', (sourceRoot as any)._node) as unknown as BieFlatNode);
    const targetRoot = new BieUpliftTargetFlatNode(
      createNode(0, 'ASBIEP') as unknown as BieFlatNode);
    const targetReuse = new BieUpliftTargetFlatNode(
      createNode(1, 'ASBIEP', (targetRoot as any)._node) as unknown as BieFlatNode);
    sourceReuse._node.reused = true;
    (targetReuse as any)._node.asccpNode = {manifestId: 1};
    sourceRoot.children = [sourceReuse];
    targetRoot.children = [targetReuse];
    sourceReuse.target = targetReuse;
    targetReuse.source = sourceReuse;
    const rootNode = new BieEditAbieNode();

    (component as any).dialog = {open: vi.fn().mockReturnValue({afterClosed: () => of(42)})};
    (component as any).hasMappedParentPair = vi.fn(() => true);
    (component as any).bieEditService = {
      getRootNode: vi.fn(() => of(rootNode)),
      getUsedBieList: vi.fn(() => of([])),
      getRefBieList: vi.fn(() => of([]))
    };
    (component as any).targetDataSource = {
      database: {
        appendUsedBieList: vi.fn(),
        appendRefBieList: vi.fn()
      },
      data: [targetRoot],
      isExpanded: vi.fn(() => false),
      expand: vi.fn(),
      dataChange: {next: vi.fn()}
    };
    (component as any).sourceDataSource = {
      data: [sourceRoot],
      expand: vi.fn(),
      dataChange: {next: vi.fn()}
    };

    component.matchReused(targetReuse as unknown as BieUpliftTargetFlatNode);

    expect(targetReuse.reusedTopLevelAsbiepId).toBe(42);
    expect((targetReuse as any)._node.topLevelAsbiepId).toBe(42);
  });

  it('hides source checkboxes for descendants mapped by a selected reuse BIE', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const source = createNode(2, 'BBIEP');
    const target = createNode(2, 'BBIEP');
    source.target = target;
    target.reuseMapped = true;

    expect(component.isSourceNodeCheckable(source as unknown as BieUpliftSourceFlatNode)).toBe(false);

    target.reuseMapped = false;
    expect(component.isSourceNodeCheckable(source as unknown as BieUpliftSourceFlatNode)).toBe(true);

    const sourceReuse = createNode(1, 'ASBIEP');
    const sourceUnmatchedChild = createNode(2, 'BBIEP', sourceReuse);
    const targetReuse = createNode(1, 'ASBIEP');
    targetReuse.reusedTopLevelAsbiepId = 99;
    sourceReuse.target = targetReuse;
    targetReuse.source = sourceReuse;
    (component as any).mappedTargetBySource = new Map([[sourceReuse, targetReuse]]);

    expect(component.isSourceNodeCheckable(
      sourceUnmatchedChild as unknown as BieUpliftSourceFlatNode)).toBe(false);
  });

  it('clears manual descendant mappings when a reuse BIE is selected', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = new BieUpliftSourceFlatNode(createNode(0, 'ASBIEP') as unknown as BieFlatNode);
    const sourceReuse = new BieUpliftSourceFlatNode(createNode(1, 'ASBIEP', (sourceRoot as any)._node) as unknown as BieFlatNode);
    const sourceChild = new BieUpliftSourceFlatNode(createNode(2, 'BBIEP', (sourceReuse as any)._node) as unknown as BieFlatNode);
    const targetRoot = new BieUpliftTargetFlatNode(createNode(0, 'ASBIEP') as unknown as BieFlatNode);
    const targetReuse = new BieUpliftTargetFlatNode(createNode(1, 'ASBIEP', (targetRoot as any)._node) as unknown as BieFlatNode);
    const targetManualChild = new BieUpliftTargetFlatNode(
      createNode(2, 'BBIEP', (targetReuse as any)._node) as unknown as BieFlatNode);
    const targetChild = new BieUpliftTargetFlatNode(createNode(2, 'BBIEP', (targetReuse as any)._node) as unknown as BieFlatNode);

    sourceRoot.children = [sourceReuse];
    sourceReuse.children = [sourceChild];
    targetRoot.children = [targetReuse];
    targetReuse.children = [targetManualChild, targetChild];
    sourceReuse.target = targetReuse;
    targetReuse.source = sourceReuse;
    sourceChild.target = targetManualChild;
    targetManualChild.source = sourceChild;

    component.sourceDataSource = {
      data: [sourceRoot],
      expand: vi.fn(),
      dataChange: {next: vi.fn()}
    } as unknown as BieFlatNodeDataSource<BieUpliftSourceFlatNode>;
    component.targetDataSource = {
      data: [targetRoot],
      isExpanded: vi.fn(() => false),
      expand: vi.fn((node: BieUpliftTargetFlatNode) => node.children = [targetChild]),
      dataChange: {next: vi.fn()}
    } as unknown as BieFlatNodeDataSource<BieUpliftTargetFlatNode>;

    (component as any).registerMapping(sourceChild, targetManualChild);
    (component as any).applyReuseSelection(targetReuse, 99, new BieEditAbieNode());

    expect(sourceChild.target).toBe(targetChild);
    expect(targetChild.source).toBe(sourceChild);
    expect(targetChild.reuseMapped).toBe(true);
    expect(targetReuse.reusedTopLevelAsbiepId).toBe(99);
    expect(targetManualChild.source).toBeUndefined();
    expect(targetManualChild.reuseMapped).toBe(false);
  });

  it('serializes the selected reuse BIE reference into the uplift request', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const target = {
      path: '/BOM/BOM Item Data',
      parents: [],
      reusedTopLevelAsbiepId: 17,
      _node: {asccNode: {manifestId: 20}}
    };
    const source = {
      level: 1,
      reused: true,
      locked: false,
      type: 'ASBIEP',
      bieId: 15,
      path: '/BOM/BOM Item Data',
      upliftPath: '/BOM/BOM Item Data',
      _node: {asccNode: {manifestId: 10}},
      target
    };
    const createUpliftBie = vi.fn(() => of({topLevelAsbiepId: 99}));
    (component as any).sourceDataSource = {data: [source]};
    (component as any).targetDataSource = {data: []};
    (component as any).bieUpliftService = {createUpliftBie};
    (component as any).auth = {getUserToken: vi.fn(() => 'test-token')};
    (component as any).router = {navigateByUrl: vi.fn()};
    component.topLevelAsbiepId = 1;
    component.targetAsccpManifestId = 2;

    component.createUpliftBIE();

    expect(createUpliftBie).toHaveBeenCalledWith(
      1,
      2,
      [expect.objectContaining({refTopLevelAsbiepId: 17})]
    );
  });

  it('uses the full uplift path for descendants of an unselected reuse', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ABIE');
    (sourceRoot as any).path = 'ASCCP-1>ACC-2';
    const sourceReuse = createNode(1, 'ASBIEP', sourceRoot);
    sourceReuse.reused = true;
    (sourceReuse as any).asccNode = {manifestId: 3};
    (sourceReuse as any).asccpNode = {manifestId: 4};
    (sourceReuse as any).accNode = {manifestId: 9};
    const sourceChild = createNode(2, 'BBIEP', sourceReuse);
    (sourceChild as any).bccNode = {manifestId: 5};
    (sourceChild as any).bccpNode = {manifestId: 6};
    (sourceChild as any).bdtNode = {manifestId: 7};
    const sourceChildSc = createNode(3, 'BBIE_SC', sourceChild);
    (sourceChildSc as any).bdtScNode = {manifestId: 8};

    const wrappedChild = new BieUpliftSourceFlatNode(sourceChild as unknown as BieFlatNode);
    const wrappedChildSc = new BieUpliftSourceFlatNode(sourceChildSc as unknown as BieFlatNode);

    expect(wrappedChild.upliftPath).toBe('ASCCP-1>ACC-2>ASCC-3>ASCCP-4>ACC-9>BCC-5');
    expect(wrappedChildSc.upliftPath).toBe(
      'ASCCP-1>ACC-2>ASCC-3>ASCCP-4>ACC-9>BCC-5>BCCP-6>DT-7>DT_SC-8');
  });

  it('keeps intermediate account nodes in the uplift path', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ABIE');
    (sourceRoot as any).path = 'ASCCP-1>ACC-2';
    const sourceReuse = createNode(1, 'ASBIEP', sourceRoot);
    (sourceReuse as any).asccNode = {manifestId: 3};
    (sourceReuse as any).asccpNode = {manifestId: 4};
    (sourceReuse as any).accNode = {manifestId: 9};
    (sourceReuse as any).intermediateAccNodes = [{type: 'ACC', manifestId: 10}];
    const sourceChild = createNode(2, 'BBIEP', sourceReuse);
    (sourceChild as any).bccNode = {manifestId: 5};
    (sourceChild as any).intermediateAccNodes = [{type: 'ACC', manifestId: 11}];

    const wrappedReuse = new BieUpliftSourceFlatNode(sourceReuse as unknown as BieFlatNode);
    const wrappedChild = new BieUpliftSourceFlatNode(sourceChild as unknown as BieFlatNode);

    expect(wrappedReuse.upliftPath).toBe('ASCCP-1>ACC-2>ACC-10>ASCC-3');
    expect(wrappedChild.upliftPath).toBe(
      'ASCCP-1>ACC-2>ACC-10>ASCC-3>ASCCP-4>ACC-9>ACC-11>BCC-5');
  });

  it('serializes manual mappings below an unselected reuse with uplift paths', () => {
    const component = Object.create(BieUpliftComponent.prototype) as BieUpliftComponent;
    const sourceRoot = createNode(0, 'ABIE');
    (sourceRoot as any).path = 'ASCCP-1>ACC-2';
    const sourceReuse = createNode(1, 'ASBIEP', sourceRoot);
    sourceReuse.reused = true;
    (sourceReuse as any).asccNode = {manifestId: 3};
    (sourceReuse as any).asccpNode = {manifestId: 4};
    (sourceReuse as any).accNode = {manifestId: 9};
    const sourceParty = createNode(2, 'ASBIEP', sourceReuse);
    (sourceParty as any).asccNode = {manifestId: 10};
    (sourceParty as any).asccpNode = {manifestId: 11};
    (sourceParty as any).accNode = {manifestId: 12};
    const sourceIdentifier = createNode(3, 'ASBIEP', sourceParty);
    (sourceIdentifier as any).asccNode = {manifestId: 13};
    (sourceIdentifier as any).asccpNode = {manifestId: 14};
    (sourceIdentifier as any).accNode = {manifestId: 15};
    const sourceTypeCode = createNode(3, 'BBIEP', sourceParty);
    (sourceTypeCode as any).bccNode = {manifestId: 16};
    const sourceSchemeAgencyIdentifier = createNode(3, 'BBIEP', sourceParty);
    (sourceSchemeAgencyIdentifier as any).bccNode = {manifestId: 19};

    const makeTarget = (type: string, path: string, manifestId: number) => {
      const target = {
        path,
        parents: [],
        reusedTopLevelAsbiepId: undefined,
        _node: {asccNode: {manifestId}, bccNode: {manifestId}}
      };
      if (type === 'ASBIEP') {
        target._node.asccNode = {manifestId};
      } else {
        target._node.bccNode = {manifestId};
      }
      return target;
    };
    const wrappedSourceReuse = new BieUpliftSourceFlatNode(sourceReuse as unknown as BieFlatNode);
    const wrappedParty = new BieUpliftSourceFlatNode(sourceParty as unknown as BieFlatNode);
    const wrappedIdentifier = new BieUpliftSourceFlatNode(sourceIdentifier as unknown as BieFlatNode);
    const wrappedTypeCode = new BieUpliftSourceFlatNode(sourceTypeCode as unknown as BieFlatNode);
    const wrappedSchemeAgencyIdentifier = new BieUpliftSourceFlatNode(
      sourceSchemeAgencyIdentifier as unknown as BieFlatNode);
    wrappedParty.target = makeTarget('ASBIEP', 'TARGET>ACCOUNT', 101);
    wrappedIdentifier.target = makeTarget('ASBIEP', 'TARGET>ACCOUNT>IDENTIFIER', 102);
    wrappedTypeCode.target = makeTarget('BBIEP', 'TARGET>ACCOUNT>ACTION', 201);
    wrappedSchemeAgencyIdentifier.target = makeTarget('BBIEP', 'TARGET>ACCOUNT>SCHEME', 202);

    const createUpliftBie = vi.fn(() => of({topLevelAsbiepId: 99}));
    (component as any).sourceDataSource = {
      data: [wrappedSourceReuse, wrappedParty, wrappedIdentifier, wrappedTypeCode, wrappedSchemeAgencyIdentifier]
    };
    (component as any).targetDataSource = {data: []};
    (component as any).bieUpliftService = {createUpliftBie};
    (component as any).auth = {getUserToken: vi.fn(() => 'test-token')};
    (component as any).router = {navigateByUrl: vi.fn()};
    component.topLevelAsbiepId = 1;
    component.targetAsccpManifestId = 2;

    component.createUpliftBIE();

    const mappings = createUpliftBie.mock.calls[0][2];
    expect(mappings).toEqual(expect.arrayContaining([
      expect.objectContaining({
        sourcePath: wrappedIdentifier.upliftPath,
        targetPath: 'TARGET>ACCOUNT>IDENTIFIER'
      }),
      expect.objectContaining({
        sourcePath: wrappedTypeCode.upliftPath,
        targetPath: 'TARGET>ACCOUNT>ACTION'
      }),
      expect.objectContaining({
        sourcePath: wrappedSchemeAgencyIdentifier.upliftPath,
        targetPath: 'TARGET>ACCOUNT>SCHEME'
      })
    ]));
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
