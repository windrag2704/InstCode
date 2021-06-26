import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

public class InstrumentFunctionVisitor extends ModifierVisitor<Void> {
    @Override
    public MethodCallExpr visit(MethodCallExpr mce, Void arg) {
        if (!mce.getScope().isPresent() || mce.getScope().get().toString().equals("ThWriter")) return mce;
        ResolvedMethodDeclaration rmd = mce.resolve();
        if (ProjectInformation.synMethods.contains(rmd.getQualifiedName())) {
            Node node = mce.getParentNode().get().getParentNode().get().getParentNode().get();
            String mName = mce.getNameAsString();
            if (node instanceof MethodDeclaration) {
                String name = ((MethodDeclaration) node).getNameAsString();
                if (name.equals(mName+ProjectInformation.METHOD_AFFIX)) return mce;
            }
            mce.setName(mce.getNameAsString() + ProjectInformation.METHOD_AFFIX);
        }
        return mce;
    }
}
