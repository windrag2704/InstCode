import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithBody;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.HashMap;
import java.util.Map;

public class InstrumentVisitor extends ModifierVisitor<Boolean> {
    private interface InstFunction {
        void instrument(BlockStmt bs, Node elem, MethodCallExpr mce);
    }

    private final HashMap<String, InstFunction> functions = new HashMap<>();

    public InstrumentVisitor() {
        super();
        InstFunction enterSyn = (bs, elem, mce) -> {
            BlockStmt newBs = new BlockStmt();
            String scope = mce.getScope().get().toString();
            newBs.addStatement(0,
                    StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                            "\" SYNWAIT \" + System.identityHashCode(" + scope + ") + \" " + scope + "\");"));
            newBs.addStatement(1, (Statement) elem.clone());
            newBs.addStatement(2,
                    StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                            "\" SYNENTER \" + System.identityHashCode(" + scope + "));"));
            bs.replace(elem, newBs);
        };
        InstFunction exitSyn = (bs, elem, mce) -> {
            BlockStmt newBs = new BlockStmt();
            String scope = mce.getScope().get().toString();
            newBs.addStatement(0,
                    StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                            "\" SYNEXIT \" + System.identityHashCode(" + scope + "));"));
            newBs.addStatement(1, (Statement) elem.clone());
            bs.replace(elem, newBs);
        };
        for (Map.Entry<String, String> entry : ConfigParser.getParsed().entrySet()) {
            switch (entry.getValue()) {
                case "SYNENTER":
                    functions.put(entry.getKey(), enterSyn);
                    break;
                case "SYNEXIT":
                    functions.put(entry.getKey(), exitSyn);
                    break;
                default:
                    throw new RuntimeException("Invalid config file");
            }
        }
    }

    @Override
    public Node visit(MethodCallExpr mce, Boolean arg) {
        super.visit(mce, arg);
        ResolvedMethodDeclaration rmd = mce.resolve();
        String s = rmd.getQualifiedName();
        Node parent = mce;
        Node elem;
        do {
            elem = parent;
            parent = parent.getParentNode().get();
        } while (!(parent instanceof BlockStmt));
        BlockStmt bs = (BlockStmt) parent;
        int index = bs.getStatements().indexOf(elem);
        String scope;
        switch (s) {
            case "java.lang.Object.wait":
                scope = mce.getScope().get().toString();
                TryStmt ts = new TryStmt();
                ts.setTryBlock(new BlockStmt());
                ts.setFinallyBlock(new BlockStmt());
                ts.getTryBlock().addStatement(0,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" PREWAIT \" + System.identityHashCode(" + scope + ") + \" " + scope + "\");"));
                ts.getTryBlock().addStatement(1,
                        bs.getStatement(index));
                ts.getFinallyBlock().get().addStatement(0,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" POSTWAIT \" + System.identityHashCode(" + scope + "));"));
                bs.getStatements().set(index, ts);
                break;
            case "java.lang.Object.notify":
                scope = mce.getScope().get().toString();
                bs.addStatement(index,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" NOTIFY \" + System.identityHashCode(" + scope + "));"));
                break;
            case "java.lang.Object.notifyAll":
                scope = mce.getScope().get().toString();
                bs.addStatement(index,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" NOTIFYALL \" + System.identityHashCode(" + scope + "));"));
                break;
            case "java.io.BufferedReader.readLine":
            case "java.io.InputStreamReader.read":
                scope = mce.getScope().get().toString();
                bs.addStatement(index,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" READSTART \" + System.identityHashCode(" + scope + ") + \" " + scope + "\");"));
                bs.addStatement(index + 2,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" READEND \" + System.identityHashCode(" + scope + "));"));
                break;
            case "java.io.Writer.write":
                scope = mce.getScope().get().toString();
                bs.addStatement(index,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" WRITESTART \" + System.identityHashCode(" + scope + ") + \" " + scope + "\");"));
                bs.addStatement(index + 2,
                        StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                "\" WRITEEND \" + System.identityHashCode(" + scope + "));"));
                break;
            case "java.lang.Thread.start":
                if (mce.getScope().get().isObjectCreationExpr()) {
                    ObjectCreationExpr oce = (ObjectCreationExpr) mce.getScope().get();
                    BlockStmt newBs = new BlockStmt();
                    VariableDeclarationExpr vde = new VariableDeclarationExpr(new VariableDeclarator(oce.getType(), "instThread", oce.clone()));
                    newBs.addStatement(0, vde);
                    newBs.addStatement(1,
                            StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                    "\" THREADRUN \" + instThread.getId() + \" anonymous \");"));
                    newBs.addStatement(2,
                            StaticJavaParser.parseStatement("instThread.start();"));

                    bs.replace(elem, newBs);

                } else {
                    scope = mce.getScope().get().toString();
                    bs.addStatement(index,
                            StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                    "\" THREADRUN \" + " + scope + ".getId());"));
                }
                break;
        }
        if (functions.get(s) != null) {
            functions.get(s).instrument(bs, elem, mce);
        }
        return mce;
    }

    @SuppressWarnings("rawtypes")
    private Visitable instrumentCycle(Statement cycle) {
        NodeWithBody nwb = (NodeWithBody) cycle;
        BlockStmt bs;
        if (nwb.getBody().isBlockStmt()) {
            bs = nwb.getBody().asBlockStmt();
        } else {
            bs = new BlockStmt();
            bs.addStatement(nwb.getBody());
        }
        bs.addStatement(0, StaticJavaParser.parseStatement(
                "ThWriter.write(Thread.currentThread().getId() +" +
                        "\" CYCLESTART \");"));
        TryStmt ts = new TryStmt();
        nwb.setBody(ts);
        ts.setTryBlock(bs);
        ts.setFinallyBlock(new BlockStmt());
        ts.getFinallyBlock().get().addStatement(
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" CYCLEEND \");"));
        TryStmt result = new TryStmt();
        result.setTryBlock(new BlockStmt());
        result.setFinallyBlock(new BlockStmt());
        result.getTryBlock().addStatement(0, ((Statement) nwb).clone());
        result.getTryBlock().addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" CYCLEENTER \");"));
        result.getFinallyBlock().get().addStatement(
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" CYCLEEXIT \");"));
        return result;
    }

    @Override
    public Visitable visit(ForStmt fs, Boolean arg) {
        super.visit(fs, arg);
        return instrumentCycle(fs);
    }

    @Override
    public Visitable visit(ForEachStmt fes, Boolean arg) {
        super.visit(fes, arg);
        return instrumentCycle(fes);
    }

    @Override
    public Visitable visit(WhileStmt ws, Boolean arg) {
        super.visit(ws, arg);
        return instrumentCycle(ws);
    }

    @Override
    public Visitable visit(DoStmt ds, Boolean arg) {
        super.visit(ds, arg);
        return instrumentCycle(ds);
    }

    private BlockStmt instrumentRunMethod(BlockStmt bs, ResolvedMethodDeclaration rmd) {
        int index = bs.getStatements().size();
        BlockStmt bts = bs.clone();
        String name = "";
        if (rmd == null) {
            name = "_.run";
        } else {
            name = rmd.getQualifiedName();
        }
        TryStmt ts = new TryStmt();
        ts.setTryBlock(bts);
        ts.setFinallyBlock(new BlockStmt());
        bts.addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" FUNCENTER " + name + "\");"));
        ts.getFinallyBlock().get().addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" THREADEND \");"));
        ts.getFinallyBlock().get().addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" FUNCEND \");"));
        BlockStmt result = new BlockStmt();
        result.addStatement(ts);
        return result;
    }

    private BlockStmt instrumentMainMethod(BlockStmt bs) {
        int index = bs.getStatements().size();
        BlockStmt bts = bs.clone();
        bts.addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" MAINTHREAD \");"));
        bts.addStatement(1,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" FUNCENTER main \");"));
        TryStmt ts = new TryStmt();
        ts.setTryBlock(bts);
        ts.setFinallyBlock(new BlockStmt());
        ts.getFinallyBlock().get().addStatement(0,
                StaticJavaParser.parseStatement("while (Thread.activeCount() > 1) { try {Thread.sleep(1000);}" +
                        " catch (InterruptedException ignored){}}"));
        ts.getFinallyBlock().get().addStatement(1,
                StaticJavaParser.parseStatement("ThWriter.join();"));
        BlockStmt result = new BlockStmt();
        result.addStatement(ts);
        return result;
    }

    private BlockStmt instrumentSynMethod(BlockStmt bs, MethodDeclaration md, ResolvedReferenceTypeDeclaration rrtd) {
        int index = bs.getStatements().size();
        boolean isStatic = md.isStatic();
        ResolvedMethodDeclaration rmd = md.resolve();
        BlockStmt bts = bs.clone();
        TryStmt ts = new TryStmt();
        ts.setTryBlock(bts);
        ts.setFinallyBlock(new BlockStmt());
        bts.addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" FUNCENTER " + rmd.getQualifiedName() + "\");"));
        ts.getFinallyBlock().get().addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" FUNCEND \");"));
        if (isStatic) {
            bts.addStatement(0,
                    StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                            "\" SYNENTER " + rrtd.getQualifiedName() + "\");"));
            ts.getFinallyBlock().get().addStatement(0,
                    StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                            "\" SYNEXIT " + rrtd.getQualifiedName() + "\");"));
        } else {
            bts.addStatement(0,
                    StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                            "\" SYNENTER \" + System.identityHashCode(this));"));
            ts.getFinallyBlock().get().addStatement(0,
                    StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                            "\" SYNEXIT \" + System.identityHashCode(this));"));

        }
        BlockStmt result = new BlockStmt();
        result.addStatement(ts);
        return result;
    }

    private void instrumentFunc(MethodDeclaration md) {
        ResolvedMethodDeclaration rmd = md.resolve();
        String name = rmd.getQualifiedName();
        if (!md.getBody().isPresent()) {
            return;
        }
        BlockStmt bs = md.getBody().get();
        int index = bs.getStatements().size();
        boolean isStatic = md.isStatic();
        BlockStmt bts = bs.clone();
        TryStmt ts = new TryStmt();
        ts.setTryBlock(bts);
        ts.setFinallyBlock(new BlockStmt());
        bts.addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" FUNCENTER " + name + "\");"));
        ts.getFinallyBlock().get().addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" FUNCEND \");"));
        BlockStmt result = new BlockStmt();
        result.addStatement(ts);
        md.setBody(result);

    }

    @Override
    public MethodDeclaration visit(MethodDeclaration md, Boolean arg) {
        ResolvedMethodDeclaration rmd = md.resolve();
        if (md.getName().asString().equals("run")) {
            Node p = md.getParentNode().get();
            ResolvedReferenceTypeDeclaration rrtd;
            if (p instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clasz = (ClassOrInterfaceDeclaration) p;
                rrtd = clasz.resolve();
            } else {
                ObjectCreationExpr oce = (ObjectCreationExpr) p;
                ResolvedConstructorDeclaration rcd = oce.resolve();
                rrtd = rcd.declaringType();
            }
            boolean instr = false;
            for (ResolvedReferenceType r : rrtd.getAllAncestors()) {
                String s = r.getQualifiedName();
                if (s.equals("java.lang.Runnable")) {
                    instr = true;
                    break;
                }
            }
            super.visit(md, instr);
            if (instr) {
                md.setBody(instrumentRunMethod(md.getBody().get(), rmd));
            }

        } else {
            super.visit(md, false);
            if (md.isStatic() && md.getModifiers().contains(Modifier.publicModifier()) && md.getNameAsString().equals("main")) {
                md.setBody(instrumentMainMethod(md.getBody().get()));
            } else {
                if (md.isSynchronized()) {
                    ClassOrInterfaceDeclaration parent = (ClassOrInterfaceDeclaration) md.getParentNode().get();
                    ResolvedReferenceTypeDeclaration rrtd = parent.resolve();
                    MethodDeclaration instMd = parent.addMethod(
                            md.getNameAsString() + ProjectInformation.METHOD_AFFIX,
                            Modifier.Keyword.PUBLIC);
                    if (md.isStatic()) {
                        instMd.addModifier(Modifier.Keyword.STATIC);
                    }
                    instMd.setType(md.getType());
                    instMd.setParameters(md.getParameters());
                    instMd.setBody(new BlockStmt());
                    MethodCallExpr instMce = new MethodCallExpr();
                    instMce.setName(md.getNameAsString());
                    NodeList<Expression> args = new NodeList<>();
                    for (int i = 0; i < instMd.getParameters().size(); i++) {
                        instMce.addArgument(new NameExpr(instMd.getParameter(i).getName()));
                    }
                    instMd.getBody().get().addStatement(new ReturnStmt(instMce));
                    instMd.getBody().get().addStatement(0,
                            StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                                    "\" SYNWAIT " + rrtd.getQualifiedName() +
                                    " " + rrtd.getQualifiedName() + " \");"));
                    ProjectInformation.synMethods.add(rmd.getQualifiedName());
                    md.setBody(instrumentSynMethod(md.getBody().get(), md, rrtd));
                } else {
                    instrumentFunc(md);
                }
            }
        }
        return md;
    }

    @Override
    public LambdaExpr visit(LambdaExpr le, Boolean arg) {
        Node p = le.getParentNode().get();
        ResolvedReferenceType rrt;
        if (p instanceof AssignExpr) {
            AssignExpr ae = (AssignExpr) p;
            ResolvedType rt = ae.getTarget().calculateResolvedType();
            rrt = rt.asReferenceType();
        } else if (p instanceof VariableDeclarator) {
            VariableDeclarator vd = (VariableDeclarator) p;
            ResolvedValueDeclaration rvd = vd.resolve();
            rrt = rvd.getType().asReferenceType();
        } else if (p instanceof ObjectCreationExpr) {
            ObjectCreationExpr oce = (ObjectCreationExpr) p;
            int index = oce.getArguments().indexOf(le);
            ResolvedConstructorDeclaration rcd = oce.resolve();
            ResolvedParameterDeclaration rpd = rcd.getParam(index);
            rrt = rpd.getType().asReferenceType();
        } else {
            MethodCallExpr mce = (MethodCallExpr) p;
            int index = mce.getArguments().indexOf(le);
            ResolvedMethodDeclaration rmd = mce.resolve();
            ResolvedParameterDeclaration rpd = rmd.getParam(index);
            rrt = rpd.getType().asReferenceType();
        }
        if (rrt.getQualifiedName().equals("java.lang.Runnable")) {
            super.visit(le, true);
            Statement body = le.getBody();
            BlockStmt bs;
            if (body instanceof BlockStmt) {
                bs = (BlockStmt) body;
            } else {
                bs = new BlockStmt();
                bs.addStatement(body);
            }
            bs = instrumentRunMethod(bs, null);
            le.setBody(bs);
        } else super.visit(le, false);
        return le;
    }

    @Override
    public Statement visit(SynchronizedStmt ss, Boolean arg) {
        super.visit(ss, arg);
        BlockStmt bs = new BlockStmt();
        SynchronizedStmt sns = ss.clone();
        bs.addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" SYNWAIT \" + System.identityHashCode(" + sns.getExpression().toString() + ") + \" " +
                        sns.getExpression().toString() + "\");"));
        sns.getBody().addStatement(0,
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" ENTERSYN \" + System.identityHashCode(" + sns.getExpression().toString() + "));"));
        sns.getBody().addStatement(sns.getBody().getStatements().size(),
                StaticJavaParser.parseStatement("ThWriter.write(Thread.currentThread().getId() +" +
                        "\" EXITSYN \" + System.identityHashCode(" + sns.getExpression().toString() + "));"));
        bs.addStatement(1, sns);
        return bs;
    }
}
