package org.benf.cfr.reader.bytecode.analysis.opgraph;

import org.benf.cfr.reader.bytecode.AnonymousClassUsage;
import org.benf.cfr.reader.bytecode.BytecodeMeta;
import org.benf.cfr.reader.bytecode.analysis.loc.BytecodeLoc;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.*;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.checker.Op04Checker;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.*;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.util.MiscStatementTools;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.*;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.FieldVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.LocalVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ConstantFoldingRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.LiteralRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.*;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.LValueScopeDiscoverImpl;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.AbstractLValueScopeDiscoverer;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.LocalClassScopeDiscoverImpl;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.*;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.placeholder.BeginBlock;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.placeholder.EndBlock;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.bytecode.analysis.types.MethodPrototype;
import org.benf.cfr.reader.bytecode.analysis.variables.VariableFactory;
import org.benf.cfr.reader.entities.*;
import org.benf.cfr.reader.entities.attributes.AttributeCode;
import org.benf.cfr.reader.entities.attributes.AttributeTypeAnnotations;
import org.benf.cfr.reader.state.ClassCache;
import org.benf.cfr.reader.state.DCCommonState;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.*;
import org.benf.cfr.reader.util.collections.*;
import org.benf.cfr.reader.util.getopt.Options;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.benf.cfr.reader.util.output.Dumpable;
import org.benf.cfr.reader.util.output.Dumper;
import org.benf.cfr.reader.util.output.LoggerFactory;

import java.util.*;
import java.util.logging.Logger;

public class Op04StructuredStatement implements MutableGraph<Op04StructuredStatement>, Dumpable, StatementContainer<StructuredStatement>, TypeUsageCollectable {
    private static final Logger logger = LoggerFactory.create(Op04StructuredStatement.class);
    private InstrIndex instrIndex;
    // Should we be bothering with sources and targets?  Not once we're "Properly" structured...
    private List<Op04StructuredStatement> sources = ListFactory.newList();
    private List<Op04StructuredStatement> targets = ListFactory.newList();
    private StructuredStatement structuredStatement;

    private Set<BlockIdentifier> blockMembership;
    // Handy for really icky breakpointing, oh I wish we had proper conditional compilation.
//    private static int id = 0;
//    private final int idx = id++;

    private static final Set<BlockIdentifier> EMPTY_BLOCKSET = SetFactory.newSet();

    private static Set<BlockIdentifier> blockSet(Collection<BlockIdentifier> in) {
        if (in == null || in.isEmpty()) return EMPTY_BLOCKSET;
        return SetFactory.newSet(in);
    }

    public Op04StructuredStatement(
            StructuredStatement justStatement
    ) {
        this.structuredStatement = justStatement;
        this.instrIndex = new InstrIndex(-1000);
        this.blockMembership = EMPTY_BLOCKSET;
        justStatement.setContainer(this);
    }

    public Op04StructuredStatement(
            InstrIndex instrIndex,
            Collection<BlockIdentifier> blockMembership,
            StructuredStatement structuredStatement) {
        this.instrIndex = instrIndex;
        this.structuredStatement = structuredStatement;
        this.blockMembership = blockSet(blockMembership);
        structuredStatement.setContainer(this);
    }

    /*
     * If we've got any anonymous classes, and we're J10+,
     * then see if we are addressing non-existent content of anonymous objects.
     * If we are, this indicates that var was used.
     */
    public static void rewriteExplicitTypeUsages(Method method, Op04StructuredStatement block, AnonymousClassUsage anonymousClassUsage, ClassFile classFile) {
        new ObjectTypeUsageRewriter(anonymousClassUsage, classFile).transform(block);
    }

    public static void flattenNonReferencedBlocks(Op04StructuredStatement block) {
        block.transform(new UnusedAnonymousBlockFlattener(), new StructuredScope());
    }

    public static void switchExpression(Method method, Op04StructuredStatement root , DecompilerComments comments) {
        SwitchExpressionRewriter switchExpressionRewriter = new SwitchExpressionRewriter(comments, method);
        switchExpressionRewriter.transform(root);
    }

    public static void reduceClashDeclarations(Op04StructuredStatement root, BytecodeMeta bytecodeMeta) {
        if (bytecodeMeta.getLivenessClashes().isEmpty()) return;
        root.transform(new ClashDeclarationReducer(bytecodeMeta.getLivenessClashes()), new StructuredScope());
    }

    // Later stages assume that certain instanceof operations are leaf nodes in boolean op trees.
    public static void normalizeInstanceOf(Op04StructuredStatement root, Options options, ClassFileVersion classFileVersion) {
        if (options.getOption(OptionsImpl.INSTANCEOF_PATTERN, classFileVersion)) {
            new InstanceOfTreeTransformer().transform(root);
        }
    }

    // TODO: This isn't quite right.  Should actually be removing the node.
    public Op04StructuredStatement nopThisAndReplace() {
        Op04StructuredStatement replacement = new Op04StructuredStatement(instrIndex, blockMembership, structuredStatement);
        replaceStatementWithNOP("");
        Op04StructuredStatement.replaceInSources(this, replacement);
        Op04StructuredStatement.replaceInTargets(this, replacement);
        return replacement;
    }

    @Override
    public void nopOut() {
        replaceStatementWithNOP("");
    }

    @Override
    public StructuredStatement getStatement() {
        return structuredStatement;
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        // Shouldn't be necessary, however belt & braces.
        // This means if you want to use a non-recursive collector, you collect the
        // StructuredStatement directly.
        if (!collector.isStatementRecursive()) return;
        structuredStatement.collectTypeUsages(collector);
    }

    @Override
    public StructuredStatement getTargetStatement(int idx) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getLabel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public InstrIndex getIndex() {
        return instrIndex;
    }

    @Override
    public void replaceStatement(StructuredStatement newTarget) {
        structuredStatement = newTarget;
        newTarget.setContainer(this);
    }

    @Override
    public void nopOutConditional() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSAIdentifiers<LValue> getSSAIdentifiers() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<BlockIdentifier> getBlockIdentifiers() {
        return blockMembership;
    }

    @Override
    public BlockIdentifier getBlockStarted() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<BlockIdentifier> getBlocksEnded() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copyBlockInformationFrom(StatementContainer<StructuredStatement> other) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copyBytecodeInformationFrom(StatementContainer<StructuredStatement> other) {
        throw new UnsupportedOperationException();
    }

    private boolean hasUnstructuredSource() {
        for (Op04StructuredStatement source : sources) {
            if (!source.structuredStatement.isProperlyStructured()) {
                return true;
            }
        }
        return false;
    }


    public Collection<BlockIdentifier> getBlockMembership() {
        return blockMembership;
    }

    @Override
    public Dumper dump(Dumper dumper) {
        dumper.informBytecodeLoc(structuredStatement);
        if (hasUnstructuredSource()) {
            dumper.label(instrIndex.toString(), false).comment(sources.size() + " sources").newln();
        }
        dumper.dump(structuredStatement);
        return dumper;
    }

    @Override
    public List<Op04StructuredStatement> getSources() {
        return sources;
    }

    @Override
    public List<Op04StructuredStatement> getTargets() {
        return targets;
    }

    @Override
    public void addSource(Op04StructuredStatement source) {
        sources.add(source);
    }

    @Override
    public void addTarget(Op04StructuredStatement target) {
        targets.add(target);
    }

    public String getTargetLabel(int idx) {
        return targets.get(idx).instrIndex.toString();
    }

    // Look, this is a bit hideous.  But it doesn't seem worth extending the interfaces / visiting.
    public boolean isEmptyInitialiser() {
        List<StructuredStatement> stms = ListFactory.newList();
        this.linearizeStatementsInto(stms);
        for (StructuredStatement stm : stms) {
            if (stm instanceof BeginBlock) continue;
            if (stm instanceof EndBlock) continue;
            if (stm instanceof StructuredComment) continue;
            if (stm instanceof StructuredExpressionStatement) {
                Expression expression = ((StructuredExpressionStatement) stm).getExpression();
                if (expression instanceof SuperFunctionInvokation) {
                    if (((SuperFunctionInvokation) expression).isInit()) continue;
                }
            }
            return false;
        }
        return true;
    }

    /*
     * Take all nodes pointing at old, and point them at me.
     * Add an unconditional target of old.
     */
    private void replaceAsSource(Op04StructuredStatement old) {
        replaceInSources(old, this);
        this.addTarget(old);
        old.addSource(this);
    }

    public void replaceTarget(Op04StructuredStatement from, Op04StructuredStatement to) {
        int index = targets.indexOf(from);
        if (index == -1) {
            throw new ConfusedCFRException("Invalid target.  Trying to replace " + from + " -> " + to);
        }
        targets.set(index, to);
    }

    public void replaceSource(Op04StructuredStatement from, Op04StructuredStatement to) {
        int index = sources.indexOf(from);
        if (index == -1) {
            throw new ConfusedCFRException("Invalid source");
        }
        sources.set(index, to);
    }

    public void setSources(List<Op04StructuredStatement> sources) {
        this.sources = sources;
    }

    public void setTargets(List<Op04StructuredStatement> targets) {
        this.targets = targets;
    }

    public static void replaceInSources(Op04StructuredStatement original, Op04StructuredStatement replacement) {
        for (Op04StructuredStatement source : original.getSources()) {
            source.replaceTarget(original, replacement);
        }
        replacement.setSources(original.getSources());
        original.setSources(ListFactory.<Op04StructuredStatement>newList());
    }

    public static void replaceInTargets(Op04StructuredStatement original, Op04StructuredStatement replacement) {
        for (Op04StructuredStatement target : original.getTargets()) {
            target.replaceSource(original, replacement);
        }
        replacement.setTargets(original.getTargets());
        original.setTargets(ListFactory.<Op04StructuredStatement>newList());
    }

    /*
     * This is called far too much for transforms - should make them work on native structures
     * where possible.
     */
    public void linearizeStatementsInto(List<StructuredStatement> out) {
        structuredStatement.linearizeInto(out);
    }

    public void removeLastContinue(BlockIdentifier block) {
        if (structuredStatement instanceof Block) {
            boolean removed = ((Block) structuredStatement).removeLastContinue(block);
            logger.info("Removing last continue for " + block + " succeeded? " + removed);
        } else {
            throw new ConfusedCFRException("Trying to remove last continue, but statement isn't block");
        }
    }

    public void removeLastGoto() {
        if (structuredStatement instanceof Block) {
            ((Block) structuredStatement).removeLastGoto();
        } else {
            throw new ConfusedCFRException("Trying to remove last goto, but statement isn't a block!");
        }
    }

    public UnstructuredWhile removeLastEndWhile() {
        if (structuredStatement instanceof Block) {
            return ((Block) structuredStatement).removeLastEndWhile();
        } else {
            return null; // Can't find.
        }
    }

    public void informBlockMembership(Vector<BlockIdentifier> currentlyIn) {
        StructuredStatement replacement = structuredStatement.informBlockHeirachy(currentlyIn);
        if (replacement == null) return;
        this.structuredStatement = replacement;
        replacement.setContainer(this);
    }

    @Override
    public String toString() {
        return structuredStatement.toString();
//        return structuredStatement.getClass().getSimpleName().toString();
    }

    public void replaceStatementWithNOP(String comment) {
        this.structuredStatement = new StructuredComment(comment);
        this.structuredStatement.setContainer(this);
    }

    private boolean claimBlock(Op04StructuredStatement innerBlock, BlockIdentifier thisBlock, Vector<BlockIdentifier> currentlyIn) {
        int idx = targets.indexOf(innerBlock);
        if (idx == -1) {
            return false;
        }
        StructuredStatement replacement = structuredStatement.claimBlock(innerBlock, thisBlock, currentlyIn);
        if (replacement == null) return false;
        this.structuredStatement = replacement;
        replacement.setContainer(this);
        return true;
    }

    private static class StackedBlock {
        BlockIdentifier blockIdentifier;
        LinkedList<Op04StructuredStatement> statements;
        Op04StructuredStatement outerStart;

        private StackedBlock(BlockIdentifier blockIdentifier, LinkedList<Op04StructuredStatement> statements, Op04StructuredStatement outerStart) {
            this.blockIdentifier = blockIdentifier;
            this.statements = statements;
            this.outerStart = outerStart;
        }
    }


    /*
     * This is pretty inefficient....
     */
    private static Set<BlockIdentifier> getEndingBlocks(Stack<BlockIdentifier> wasIn, Set<BlockIdentifier> nowIn) {
        Set<BlockIdentifier> wasCopy = SetFactory.newSet(wasIn);
        wasCopy.removeAll(nowIn);
        return wasCopy;
    }

    private static BlockIdentifier getStartingBlocks(Stack<BlockIdentifier> wasIn, Set<BlockIdentifier> nowIn) {
        /*
         * We /KNOW/ that we've already checked and dealt with blocks we've left.
         * So we're only entering a new block if |nowIn|>|wasIn|.
         */
        if (nowIn.size() <= wasIn.size()) return null;
        Set<BlockIdentifier> nowCopy = SetFactory.newSet(nowIn);
        nowCopy.removeAll(wasIn);
        if (nowCopy.size() != 1) {
//            logger.warning("From " + wasIn + " to " + nowIn + " = " + nowCopy);
            throw new ConfusedCFRException("Started " + nowCopy.size() + " blocks at once");
        }
        return nowCopy.iterator().next();
    }

    private static class MutableProcessingBlockState {
        BlockIdentifier currentBlockIdentifier = null;
        LinkedList<Op04StructuredStatement> currentBlock = ListFactory.newLinkedList();
    }

    private static void processEndingBlocks(
            final Set<BlockIdentifier> endOfTheseBlocks,
            final Stack<BlockIdentifier> blocksCurrentlyIn,
            final Stack<StackedBlock> stackedBlocks,
            final MutableProcessingBlockState mutableProcessingBlockState) {
        logger.fine("statement is last statement in these blocks " + endOfTheseBlocks);

        while (!endOfTheseBlocks.isEmpty()) {
            if (mutableProcessingBlockState.currentBlockIdentifier == null) {
                throw new ConfusedCFRException("Trying to end block, but not in any!");
            }
            // Leaving a block, but
            if (!endOfTheseBlocks.remove(mutableProcessingBlockState.currentBlockIdentifier)) {
                throw new ConfusedCFRException("Tried to end blocks " + endOfTheseBlocks + ", but top level block is " + mutableProcessingBlockState.currentBlockIdentifier);
            }
            BlockIdentifier popBlockIdentifier = blocksCurrentlyIn.pop();
            if (popBlockIdentifier != mutableProcessingBlockState.currentBlockIdentifier) {
                throw new ConfusedCFRException("Tried to end blocks " + endOfTheseBlocks + ", but top level block is " + mutableProcessingBlockState.currentBlockIdentifier);
            }
            LinkedList<Op04StructuredStatement> blockJustEnded = mutableProcessingBlockState.currentBlock;
            StackedBlock popBlock = stackedBlocks.pop();
            mutableProcessingBlockState.currentBlock = popBlock.statements;
            // todo : Do I still need to get /un/structured parents right?
            Op04StructuredStatement finishedBlock = new Op04StructuredStatement(new Block(blockJustEnded, true));
            finishedBlock.replaceAsSource(blockJustEnded.getFirst());
            Op04StructuredStatement blockStartContainer = popBlock.outerStart;

            if (!blockStartContainer.claimBlock(finishedBlock, mutableProcessingBlockState.currentBlockIdentifier, blocksCurrentlyIn)) {
                mutableProcessingBlockState.currentBlock.add(finishedBlock);
            }
            mutableProcessingBlockState.currentBlockIdentifier = popBlock.blockIdentifier;
        }
    }

    public boolean isFullyStructured() {
        return structuredStatement.isRecursivelyStructured();
    }

    static Op04StructuredStatement buildNestedBlocks(List<Op04StructuredStatement> containers) {
        /*
         * the blocks we're in, and when we entered them.
         *
         * This is ugly, could keep track of this more cleanly.
         */
        Stack<BlockIdentifier> blocksCurrentlyIn = StackFactory.newStack();
        LinkedList<Op04StructuredStatement> outerBlock = ListFactory.newLinkedList();
        Stack<StackedBlock> stackedBlocks = StackFactory.newStack();

        MutableProcessingBlockState mutableProcessingBlockState = new MutableProcessingBlockState();
        mutableProcessingBlockState.currentBlock = outerBlock;

        for (Op04StructuredStatement container : containers) {
            /*
             * if this statement has the same membership as blocksCurrentlyIn, it's in the same
             * block as the previous statement, so emit it into currentBlock.
             *
             * If not, we end the blocks that have been left, in reverse order of arriving in them.
             *
             * If we've started a new block.... start that.
             */
            Set<BlockIdentifier> endOfTheseBlocks = getEndingBlocks(blocksCurrentlyIn, container.blockMembership);
            if (!endOfTheseBlocks.isEmpty()) {
                processEndingBlocks(endOfTheseBlocks, blocksCurrentlyIn, stackedBlocks, mutableProcessingBlockState);
            }

            BlockIdentifier startsThisBlock = getStartingBlocks(blocksCurrentlyIn, container.blockMembership);
            if (startsThisBlock != null) {
                logger.fine("Starting block " + startsThisBlock);
                BlockType blockType = startsThisBlock.getBlockType();
                // A bit confusing.  StartBlock for a while loop is the test.
                // StartBlock for conditionals is the first element of the conditional.
                // I need to refactor this......
                Op04StructuredStatement blockClaimer = mutableProcessingBlockState.currentBlock.getLast();

                stackedBlocks.push(new StackedBlock(mutableProcessingBlockState.currentBlockIdentifier, mutableProcessingBlockState.currentBlock, blockClaimer));
                mutableProcessingBlockState.currentBlock = ListFactory.newLinkedList();
                mutableProcessingBlockState.currentBlockIdentifier = startsThisBlock;
                blocksCurrentlyIn.push(mutableProcessingBlockState.currentBlockIdentifier);
            }

            container.informBlockMembership(blocksCurrentlyIn);
            mutableProcessingBlockState.currentBlock.add(container);


        }
        /*
         * End any blocks we're still in.
         */
        if (!stackedBlocks.isEmpty()) {
            processEndingBlocks(SetFactory.newSet(blocksCurrentlyIn), blocksCurrentlyIn, stackedBlocks, mutableProcessingBlockState);
        }
        Block result = new Block(outerBlock, true);
        return new Op04StructuredStatement(result);

    }

    private static class LabelledBlockExtractor implements StructuredStatementTransformer {
        @Override
        public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
            if (in instanceof Block) {
                Block block = (Block) in;
                block.extractLabelledBlocks();
            }
            in.transformStructuredChildren(this, scope);
            return in;
        }
    }

    private static class EmptyCatchTidier implements StructuredStatementTransformer {
        @Override
        public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
            if (in instanceof UnstructuredCatch) {
                return ((UnstructuredCatch) in).getCatchForEmpty();
            }
            in.transformStructuredChildren(this, scope);
            return in;
        }
    }

    private static class TryCatchTidier implements StructuredStatementTransformer {
        @Override
        public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
            if (in instanceof Block) {
                // Search for try statements, see if we can combine following catch statements with them.
                Block block = (Block) in;
                block.combineTryCatch();
            }
            in.transformStructuredChildren(this, scope);
            return in;
        }
    }

    private static class Inliner implements StructuredStatementTransformer {
        @Override
        public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
            in.transformStructuredChildren(this, scope);
            if (in instanceof Block) {
                Block block = (Block) in;
                block.combineInlineable();
            }
            return in;
        }
    }

    /*
     * So far I've only actually seen this be useful for sun.tools.javac.sourceClass.....
     */
    public static class UnstructuredIfConverter implements StructuredStatementTransformer {
        @Override
        public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
            in.transformStructuredChildren(this, scope);
            if (in instanceof UnstructuredIf) {
                in = ((UnstructuredIf) in).convertEmptyToGoto();
            }
            return in;
        }
    }

    private static StructuredStatement transformStructuredGotoWithScope(StructuredScope scope, StructuredStatement stm,
                                                                        Stack<Triplet<StructuredStatement, BlockIdentifier, Set<Op04StructuredStatement>>> breaktargets
    ) {
        Set<Op04StructuredStatement> nextFallThrough = scope.getNextFallThrough(stm);
        List<Op04StructuredStatement> targets = stm.getContainer().getTargets();
        // Targets is an invalid concept for op04 really, should get rid of it.
        Op04StructuredStatement target = targets.isEmpty() ? null : targets.get(0);
        if (nextFallThrough.contains(target)) {
            // Ok, fell through.  If we're the last statement of the current scope,
            // and the current scope has fallthrough, we can be removed.  Otherwise we
            // need to be translated to a break.
            if (scope.statementIsLast(stm)) {
                return StructuredComment.EMPTY_COMMENT;
            } else if (scope.getDirectFallThrough().contains(target)) {
                return StructuredComment.EMPTY_COMMENT;
            } else {
                return stm;
            }
        } else if (!breaktargets.isEmpty()) {
            // Ok - it doesn't.  But can we get there by breaking out of one of the enclosing blocks?
            Triplet<StructuredStatement, BlockIdentifier, Set<Op04StructuredStatement>> breakTarget = breaktargets.peek();
            if (breakTarget.getThird().contains(target)) {
                return new StructuredBreak(BytecodeLoc.TODO, breakTarget.getSecond(), true);
            }
        }
        return stm;
    }


    private static abstract class ScopeDescendingTransformer implements StructuredStatementTransformer {

        private final Stack<Triplet<StructuredStatement, BlockIdentifier, Set<Op04StructuredStatement>>> targets = new Stack<Triplet<StructuredStatement, BlockIdentifier, Set<Op04StructuredStatement>>>();

        protected abstract StructuredStatement doTransform(StructuredStatement statement, Stack<Triplet<StructuredStatement, BlockIdentifier, Set<Op04StructuredStatement>>> targets, StructuredScope scope);

        @Override
        public StructuredStatement transform(final StructuredStatement in, StructuredScope scope) {
            /*
             * If this statement is a breakable block, (i.e. it's a block with foreign references, a loop or the like)
             * determine what the statement after it (so effect of a break from it) would be.
             */
            final BlockIdentifier breakableBlock = in.getBreakableBlockOrNull();
            if (breakableBlock != null) {
                final Set<Op04StructuredStatement> next = scope.getNextFallThrough(in);
                targets.push(Triplet.make(in, breakableBlock, next));
            }
            StructuredStatement out = in;
            try {
                out.transformStructuredChildrenInReverse(this, scope);
                out = doTransform(out, targets, scope);
                if (out instanceof StructuredBreak) {
                    out = ((StructuredBreak) out).maybeTightenToLocal(targets);
                }
            } finally {
                if (breakableBlock != null) {
                    targets.pop();
                }
            }
            return out;
        }
    }

    // Walk block children in reverse - this allows us to skip over repeated 'last' statements
    private static class StructuredGotoRemover extends ScopeDescendingTransformer {
        @Override
        protected StructuredStatement doTransform(StructuredStatement statement, Stack<Triplet<StructuredStatement, BlockIdentifier, Set<Op04StructuredStatement>>> targets, StructuredScope scope) {
            if (statement instanceof UnstructuredGoto ||
                    statement instanceof UnstructuredAnonymousBreak) {
                statement = transformStructuredGotoWithScope(scope, statement, targets);
            }
            return statement;
        }
    }

    private static class NamedBreakRemover extends ScopeDescendingTransformer {
        @Override
        protected StructuredStatement doTransform(StructuredStatement statement, Stack<Triplet<StructuredStatement, BlockIdentifier, Set<Op04StructuredStatement>>> targets, StructuredScope scope) {
            if (statement instanceof StructuredBreak) {
                statement = ((StructuredBreak) statement).maybeTightenToLocal(targets);
            }
            return statement;
        }
    }

    private static class PointlessBlockRemover implements StructuredStatementTransformer {
        @Override
        public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
            in.transformStructuredChildren(this, scope);
            if (in instanceof CanRemovePointlessBlock) {
                ((CanRemovePointlessBlock) in).removePointlessBlocks(scope);
            }
            return in;
        }
    }

    public void transform(StructuredStatementTransformer transformer, StructuredScope scope) {
        StructuredStatement old = structuredStatement;
        StructuredStatement scopeBlock = structuredStatement.isScopeBlock() ? structuredStatement : null;
        if (scopeBlock != null) scope.add(scopeBlock);
        try {
            structuredStatement = transformer.transform(structuredStatement, scope);
            if (structuredStatement != old && structuredStatement != null) {
                structuredStatement.setContainer(this);
            }
        } finally {
            if (scopeBlock != null) scope.remove(scopeBlock);
        }
    }

    /*
     * If we have any UnstructuredAnonBreakTargets in a block, starting at the last one, pull them into sub-blocks.
     */
    public static void insertLabelledBlocks(Op04StructuredStatement root) {
        root.transform(new LabelledBlockExtractor(), new StructuredScope());
    }

    /*
     * mutually exclusive blocks may have trailling gotos after them.  It's hard to remove them prior to here, but now we have
     * structure, we can find them more easily.
     */
    public static void tidyEmptyCatch(Op04StructuredStatement root) {
        root.transform(new EmptyCatchTidier(), new StructuredScope());
    }

    public static void tidyTryCatch(Op04StructuredStatement root) {
        root.transform(new TryCatchTidier(), new StructuredScope());
    }

    public static void inlinePossibles(Op04StructuredStatement root) {
        root.transform(new Inliner(), new StructuredScope());
    }

    public static void convertUnstructuredIf(Op04StructuredStatement root) {
        root.transform(new UnstructuredIfConverter(), new StructuredScope());
    }

    public static void tidyVariableNames(Method method, Op04StructuredStatement root, BytecodeMeta bytecodeMeta, DecompilerComments comments, ClassCache classCache) {
        VariableNameTidier variableNameTidier = new VariableNameTidier(method, VariableNameTidier.NameDiscoverer.getUsedLambdaNames(bytecodeMeta, root), classCache);
        variableNameTidier.transform(root);

        if (variableNameTidier.isClassRenamed()) {
            comments.addComment(DecompilerComment.CLASS_RENAMED);
        }
    }

    public static void applyTypeAnnotations(AttributeCode code, Op04StructuredStatement root, SortedMap<Integer, Integer> instrsByOffset,
                                            DecompilerComments comments) {
        AttributeTypeAnnotations vis = code.getRuntimeVisibleTypeAnnotations();
        AttributeTypeAnnotations invis = code.getRuntimeInvisibleTypeAnnotations();
        if (vis == null && invis == null) {
            return;
        }
        TypeAnnotationTransformer transformer = new TypeAnnotationTransformer(vis, invis, instrsByOffset, comments);
        transformer.transform(root);
    }

    public static void removePointlessReturn(Op04StructuredStatement root) {
        StructuredStatement statement = root.getStatement();
        if (statement instanceof Block) {
            Block block = (Block) statement;
            block.removeLastNVReturn();
        }
    }

    public static void removeEndResource(ClassFile classFile, Op04StructuredStatement root) {
        // Note - this is not in Java9 CODE per se, it's if it's been compiled by the J9 compiler.
        boolean s1 = new TryResourcesTransformerJ9(classFile).transform(root);
        boolean s2 = new TryResourcesTransformerJ7(classFile).transform(root);
        boolean s3 = new TryResourcesTransformerJ12(classFile).transform(root);
        // Java 11
        if (s1 || s2 || s3) {
            new TryResourcesCollapser().transform(root);
        }
    }

    /*
     * If a break falls out into another break, or a continue falls out into the end of a loop, they don't need to
     * be there.
     */
    public static void removePointlessControlFlow(Op04StructuredStatement root) {
        new ControlFlowCleaningTransformer().transform(root);
    }

    public static void tidyTypedBooleans(Op04StructuredStatement root) {
        new TypedBooleanTidier().transform(root);
    }

    public static void miscKeyholeTransforms(VariableFactory variableFactory, Op04StructuredStatement root) {
        new NakedNullCaster().transform(root);
        new LambdaCleaner().transform(root);
        new TernaryCastCleaner().transform(root);
        new InvalidBooleanCastCleaner().transform(root);
        new HexLiteralTidier().transform(root);
        new ExpressionRewriterTransformer(LiteralRewriter.INSTANCE).transform(root);
        new InvalidExpressionStatementCleaner(variableFactory).transform(root);
    }

    public static void tidyObfuscation(Options options, Op04StructuredStatement root) {
        if (options.getOption(OptionsImpl.CONST_OBF)) {
            new ExpressionRewriterTransformer(ConstantFoldingRewriter.INSTANCE).transform(root);
        }
    }

    public static void prettifyBadLoops(Op04StructuredStatement root) {
        new BadLoopPrettifier().transform(root);
    }

    public static void removeStructuredGotos(Op04StructuredStatement root) {
        root.transform(new StructuredGotoRemover(), new StructuredScope());
    }

    /*
     * Named blocks can be left in when they're no longer necessary - i.e.
     *
     * public class LoopTest58 {
        public void test(int n, int n2) {
            block3 : {
                if (n < n2) {
                    for (int i = n; i < n2; ++i) {
                        System.out.print("s");
                        if (i < n2) continue;
                        System.out.print("s2");
                        break block3;
                    }
                } else {
                    System.out.print(n);
                }
            }
            System.out.println("Done");
        }
    }

        In this case, we need to detect if the statement after an anonymous block is the next
        statement out of the innermost breakable block - if that's the case, the specific reference
        to the named block is unnecessary.
     */
    public static void removeUnnecessaryLabelledBreaks(Op04StructuredStatement root) {
        root.transform(new NamedBreakRemover(), new StructuredScope());
    }

    public static void removePointlessBlocks(Op04StructuredStatement root) {
        root.transform(new PointlessBlockRemover(), new StructuredScope());
    }

    /*
     * We've got structured (hopefully) code now, so we can find the initial unbranched assignment points
     * for any given variable.
     *
     * We can also discover if stack locations have been re-used with a type change - this would have resulted
     * in what looks like invalid variable re-use, which we can now convert.
     *
     * Note - because this may lift variables to an earlier scoped declaration, we have a second pass to tidy
     * (eg remove spurious 'this.', VariableNameTidier).
     */
    public static void discoverVariableScopes(Method method, Op04StructuredStatement root, VariableFactory variableFactory, Options options, ClassFileVersion classFileVersion, BytecodeMeta bytecodeMeta) {
        LValueScopeDiscoverImpl scopeDiscoverer = new LValueScopeDiscoverImpl(options, method.getMethodPrototype(), variableFactory, classFileVersion);
        scopeDiscoverer.processOp04Statement(root);
        // We should have found scopes, now update to reflect this.
        scopeDiscoverer.markDiscoveredCreations();
        if (scopeDiscoverer.didDetectInstanceOfMatching()) {
            bytecodeMeta.set(BytecodeMeta.CodeInfoFlag.INSTANCE_OF_MATCHES);
        }
    }

    public static void discoverLocalClassScopes(Method method, Op04StructuredStatement root, VariableFactory variableFactory, Options options) {
        AbstractLValueScopeDiscoverer scopeDiscoverer = new LocalClassScopeDiscoverImpl(options, method, variableFactory);
        scopeDiscoverer.processOp04Statement(root);
        // We should have found scopes, now update to reflect this.
        scopeDiscoverer.markDiscoveredCreations();
    }

    public static void tidyInstanceMatches(Op04StructuredStatement block) {
        InstanceofMatchTidyingRewriter.rewrite(block);
    }

    public static boolean checkTypeClashes(Op04StructuredStatement block, BytecodeMeta bytecodeMeta) {
        LValueTypeClashCheck clashCheck = new LValueTypeClashCheck();
        clashCheck.processOp04Statement(block);
        Set<Integer> clashes = clashCheck.getClashes();
        if (!clashes.isEmpty()) {
            bytecodeMeta.informLivenessClashes(clashes);
            return true;
        }
        return false;
    }

    public static FieldVariable findInnerClassOuterThis(Method method, Op04StructuredStatement root) {

        MethodPrototype prototype = method.getMethodPrototype();

        List<LocalVariable> vars = prototype.getComputedParameters();
        if (vars.isEmpty()) return null;

        LocalVariable outerThis = vars.get(0);
        // Todo : Should we test that it's the right type?  Already been done, really....

        InnerClassConstructorRewriter innerClassConstructorRewriter = new InnerClassConstructorRewriter(method.getClassFile(), outerThis);
        // Not actually rewriting, just checking.
        innerClassConstructorRewriter.rewrite(root);
        FieldVariable matchedLValue = innerClassConstructorRewriter.getMatchedField();
        return matchedLValue;
    }


    public static void removeInnerClassOuterThis(Method method, Op04StructuredStatement root) {

        MethodPrototype prototype = method.getMethodPrototype();

        List<LocalVariable> vars = prototype.getComputedParameters();
        if (vars.isEmpty()) return;

        LocalVariable outerThis = vars.get(0);
        // Todo : Should we test that it's the right type?  Already been done, really....

        InnerClassConstructorRewriter innerClassConstructorRewriter = new InnerClassConstructorRewriter(method.getClassFile(), outerThis);
        // Not actually rewriting, just checking.
        innerClassConstructorRewriter.rewrite(root);
        FieldVariable matchedLValue = innerClassConstructorRewriter.getMatchedField();
        if (matchedLValue == null) {
            return;
        }

        /* If there was a value to match, we now have to replace the parameter with the member anywhere it was used
         * in the constructor.
         */

        Map<LValue, LValue> replacements = MapFactory.newMap();
        replacements.put(outerThis, matchedLValue);
        innerClassConstructorRewriter.getAssignmentStatement().getContainer().nopOut();
        prototype.setInnerOuterThis();
        prototype.hide(0);

        applyLValueReplacer(replacements, root);
    }

    private static void removeMethodScopedSyntheticConstructorOuterArgs(Method method, Op04StructuredStatement root, Set<MethodPrototype> processed) {
        final MethodPrototype prototype = method.getMethodPrototype();

        if (!processed.add(prototype)) return;

        // A local class can have both synthetic parameters AND real ones....
        List<MethodPrototype.ParameterLValue> vars = prototype.getParameterLValues();
        if (vars.isEmpty()) return;

        List<ConstructorInvokationSimple> usages = method.getClassFile().getMethodUsages();
        if (usages.isEmpty()) return;

        /*
         * Make sure this usage is appropriate to *THIS* constructor.
         */
        class CaptureExpression {
            private int idx;

            private CaptureExpression(int idx) {
                this.idx = idx;
            }

            private Set<Expression> captures = new HashSet<Expression>();
        }

        Map<MethodPrototype, MethodPrototype> protos = new IdentityHashMap<MethodPrototype, MethodPrototype>();
        Map<MethodPrototype.ParameterLValue, CaptureExpression> captured = MapFactory.newIdentityMap();

        /*
         * Note that we're aggregating the captures from ALL constructors into a single map.
         */
        for (ConstructorInvokationSimple usage : usages) {
            List<Expression> args = usage.getArgs();
            MethodPrototype proto = usage.getConstructorPrototype();
            protos.put(proto, proto);
            for (int x = 0; x < vars.size(); ++x) {
                MethodPrototype.ParameterLValue var = vars.get(x);
                if (var.isHidden() || proto.isHiddenArg(x)) {
                    CaptureExpression capture = captured.get(var);
                    if (capture == null) {
                        capture = new CaptureExpression(x);
                        captured.put(var, capture);
                    }
                    capture.captures.add(args.get(x));
                }
            }
        }

        // A method scoped class may have more than one constructor.
        // We need to correctly link to the /relevant/ one. :(
        MethodPrototype callProto = null;
        switch (protos.size()) {
            case 0:
                return;
            case 1:
                callProto = SetUtil.getSingle(protos.keySet());
                break;
            default:
                for (MethodPrototype proto : protos.keySet()) {
                    if (proto.equalsMatch(prototype)) {
                        if (callProto == null) {
                            callProto = proto;
                        } else {
                            return;
                        }
                    }
                }
        }
        if (callProto == null) return;

        ClassFile classFile = method.getClassFile();

        for (int x = 0; x < vars.size(); ++x) {
            MethodPrototype.ParameterLValue parameterLValue = vars.get(x);
            CaptureExpression captureExpression = captured.get(parameterLValue);

            if (captureExpression == null || captureExpression.captures.size() != 1) continue;

            Expression expr = captureExpression.captures.iterator().next();
            if (!(expr instanceof LValueExpression)) continue;

            LValue lValueArg = ((LValueExpression) expr).getLValue();
            String overrideName = getInnerClassOuterArgName(method, lValueArg);
            if (overrideName == null) continue;

            if (parameterLValue.hidden == MethodPrototype.HiddenReason.HiddenOuterReference) {
                if (prototype.isInnerOuterThis()) {
                    if (prototype.isHiddenArg(captureExpression.idx)) {
                        callProto.hide(captureExpression.idx);
                    }
                } else {
                    hideField(root, callProto, classFile, captureExpression.idx, parameterLValue.localVariable, lValueArg, overrideName);
                }
            } else if (parameterLValue.hidden == MethodPrototype.HiddenReason.HiddenCapture || callProto.isHiddenArg(x)) {
                hideField(root, callProto, classFile, captureExpression.idx, parameterLValue.localVariable, lValueArg, overrideName);
            }
        }
    }

    private static void removeAnonymousSyntheticConstructorOuterArgs(Method method, Op04StructuredStatement root, boolean isInstance) {
        MethodPrototype prototype = method.getMethodPrototype();
        List<LocalVariable> vars = prototype.getComputedParameters();
        if (vars.isEmpty()) return;

        Map<LValue, LValue> replacements = MapFactory.newMap();

        /*
         * In normal usage, there will be only one instance of the construction of an anonymous inner.
         * If there are multiple, then we will have an issue rewriting the inner variables to match the outer
         * ones.
         */
        List<ConstructorInvokationAnonymousInner> usages = method.getClassFile().getAnonymousUsages();

        ConstructorInvokationAnonymousInner usage = usages.size() == 1 ? usages.get(0) : null;

        if (usage == null) return;
        /* If this inner class is an anonymous inner class, it could capture outer locals directly.
         * for all the other members - we'll search for any private final members which are initialised in the constructor
         * and alias those members to the argument that called them.
         */
        List<Expression> actualArgs = usage.getArgs();
        if (actualArgs.size() != vars.size()) {
            // can't handle this.  It's probably an enum synthetic.
            return;
        }
        int start = isInstance ? 1 : 0;
        ClassFile classFile = method.getClassFile();
        for (int x = start, len = vars.size(); x < len; ++x) {
            LocalVariable protoVar = vars.get(x);
            Expression arg = actualArgs.get(x);

            arg = CastExpression.removeImplicit(arg);
            /*
             * For this to be a captured variable, it needs to not be computed - i.e. an Lvalue.
             */
            if (!(arg instanceof LValueExpression)) continue;
            LValue lValueArg = ((LValueExpression) arg).getLValue();
            String overrideName = getInnerClassOuterArgName(method, lValueArg);
            if (overrideName == null) {
                continue;
            }

            hideField(root, prototype, classFile, x, protoVar, lValueArg, overrideName);
        }

        applyLValueReplacer(replacements, root);
    }

    private static String getInnerClassOuterArgName(Method method, LValue lValueArg) {
        String overrideName = null;
        if (lValueArg instanceof LocalVariable) {
            LocalVariable localVariable = (LocalVariable) lValueArg;
            overrideName = localVariable.getName().getStringName();
        } else if (lValueArg instanceof FieldVariable) {
            FieldVariable fv = (FieldVariable) lValueArg;
            JavaTypeInstance thisClass = method.getClassFile().getClassType();
            JavaTypeInstance fieldClass = fv.getOwningClassType();
            boolean isInner = thisClass.getInnerClassHereInfo().isTransitiveInnerClassOf(fieldClass);
            if (isInner) {
                overrideName = fv.getFieldName();
            }
        }
        return overrideName;
    }

    private static void hideField(Op04StructuredStatement root, MethodPrototype prototype, ClassFile classFile, int x, LocalVariable protoVar, LValue lValueArg, String overrideName) {
        InnerClassConstructorRewriter innerClassConstructorRewriter = new InnerClassConstructorRewriter(classFile, protoVar);
        innerClassConstructorRewriter.rewrite(root);
        FieldVariable matchedField = innerClassConstructorRewriter.getMatchedField();
        if (matchedField == null) {
            return;
        }
        // Nop out the assign statement, rename the field, hide the argument.
        innerClassConstructorRewriter.getAssignmentStatement().getContainer().nopOut();
        // We need to link the name to the outer variable in such a way that if that changes name,
        // we don't lose it.
        //
        // Once this has occurred, there's a possibility that we may have caused collisions
        // between these renamed members and locals in other code.
        ClassFileField classFileField = matchedField.getClassFileField();
        classFileField.overrideName(overrideName);
        classFileField.markSyntheticOuterRef();
        classFileField.markHidden();
        prototype.hide(x);
        lValueArg.markFinal();
    }

    private static void applyLValueReplacer(Map<LValue, LValue> replacements, Op04StructuredStatement root) {
        if (!replacements.isEmpty()) {
            LValueReplacingRewriter lValueReplacingRewriter = new LValueReplacingRewriter(replacements);
            MiscStatementTools.applyExpressionRewriter(root, lValueReplacingRewriter);
        }
    }

    /*
     * Remove (and rewrite) references to this$x
     */
    public static void fixInnerClassConstructorSyntheticOuterArgs(ClassFile classFile, Method method, Op04StructuredStatement root, Set<MethodPrototype> processed) {
        if (classFile.isInnerClass()) {
            boolean instance = !classFile.testAccessFlag(AccessFlag.ACC_STATIC);
            removeAnonymousSyntheticConstructorOuterArgs(method, root, instance);
            removeMethodScopedSyntheticConstructorOuterArgs(method, root, processed);
        }
    }

    public static void tidyAnonymousConstructors(Op04StructuredStatement root) {
        root.transform(new ExpressionRewriterTransformer(new AnonymousClassConstructorRewriter()), new StructuredScope());
    }

    public static void inlineSyntheticAccessors(DCCommonState state, Method method, Op04StructuredStatement root) {
        JavaTypeInstance classType = method.getClassFile().getClassType();
        new SyntheticAccessorRewriter(state, classType).rewrite(root);
    }

    public static void removeConstructorBoilerplate(Op04StructuredStatement root) {
        new RedundantSuperRewriter().rewrite(root);
    }

    public static void rewriteLambdas(DCCommonState state, Method method, Op04StructuredStatement root) {
        Options options = state.getOptions();
        if (!options.getOption(OptionsImpl.REWRITE_LAMBDAS, method.getClassFile().getClassFileVersion())) return;

        new LambdaRewriter(state, method).rewrite(root);
    }

    public static void removeUnnecessaryVarargArrays(Options options, Method method, Op04StructuredStatement root) {
        new VarArgsRewriter().rewrite(root);
    }

    public static void removePrimitiveDeconversion(Options options, Method method, Op04StructuredStatement root) {
        if (!options.getOption(OptionsImpl.SUGAR_BOXING)) return;

        root.transform(new ExpressionRewriterTransformer(new PrimitiveBoxingRewriter()), new StructuredScope());
    }

    public static void rewriteBadCastChains(Options options, Method method, Op04StructuredStatement root) {
        root.transform(new ExpressionRewriterTransformer(new BadCastChainRewriter()), new StructuredScope());
    }

    public static void rewriteNarrowingAssignments(Options options, Method method, Op04StructuredStatement root) {
        new NarrowingAssignmentRewriter().rewrite(root);
    }

    public static void replaceNestedSyntheticOuterRefs(Op04StructuredStatement root) {
        List<StructuredStatement> statements = MiscStatementTools.linearise(root);
        //
        // It strikes me I could do this as a map replace, if I generate the set of possible rewrites.
        // probably a bit gross though ;)
        //
        if (statements == null) return;

        SyntheticOuterRefRewriter syntheticOuterRefRewriter = new SyntheticOuterRefRewriter();
        for (StructuredStatement statement : statements) {
            statement.rewriteExpressions(syntheticOuterRefRewriter);
            PointlessStructuredExpressions.removePointlessExpression(statement);
        }
    }

    /*
     * there /should/ never be any loose catch statements.
     */
    public static void applyChecker(Op04Checker checker, Op04StructuredStatement root, DecompilerComments comments) {
        StructuredScope structuredScope = new StructuredScope();
        root.transform(checker, structuredScope);
        checker.commentInto(comments);
    }

    public static boolean isTryWithResourceSynthetic(Method m, Op04StructuredStatement root) {
        return ResourceReleaseDetector.isResourceRelease(m, root);
    }
}
