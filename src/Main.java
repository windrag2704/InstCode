import com.github.javaparser.ParseResult;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class Main {
    public static void main(String[] args) {
        String PRO_ROOT;
        String INST_ROOT;
        String JARS;
        if (args.length < 2) {
            System.err.println("You must write project directory and output directory");
            return;
        } else {
            PRO_ROOT = args[0];
            INST_ROOT = args[1];
        }
        if (args.length < 3) {
            JARS = "";
        } else {
            JARS = args[2];
        }
        BufferedReader cfgReader;
        try {
            cfgReader = new BufferedReader(new FileReader("config.cfg"));
            readConfig(cfgReader);
        } catch (FileNotFoundException e) {
            System.err.println("Can't find config file");
        }
        CombinedTypeSolver ts = new CombinedTypeSolver(
                new ReflectionTypeSolver(),
                new JavaParserTypeSolver(PRO_ROOT)
        );
        if (!JARS.isEmpty()) {
            try (Stream<Path> paths = Files.walk(Paths.get(JARS))) {
                paths
                        .filter(Files::isRegularFile)
                        .forEach(file -> {
                            try {
                                ts.add(new JarTypeSolver(file));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
            } catch (IOException ioException) {
                System.err.println("Can't read jars directory");
            }
        }
        ProjectRoot pr = new SymbolSolverCollectionStrategy().collect(new File(PRO_ROOT).toPath());
        InstrumentVisitor iVisitor = new InstrumentVisitor();
        InstrumentFunctionVisitor ifVisitor = new InstrumentFunctionVisitor();
        runVisitor(pr, iVisitor);
        runVisitor(pr, ifVisitor);
        ResLoader resLoader = new ResLoader();
        File thWr;
        try {
            thWr = resLoader.getFileFromResource("ThWriter");
        } catch (URISyntaxException e) {
            System.err.println("Can't get resource file");
            return;
        }
        CompilationUnit cuWr;
        try {
            cuWr = StaticJavaParser.parse(thWr);
        } catch (FileNotFoundException e) {
            System.err.println("Can't read resource file");
            return;
        }
        String thStr = null;
        for (SourceRoot sr : pr.getSourceRoots()) {
            String newPath = sr.getRoot().toString().replace(PRO_ROOT, INST_ROOT);
            if (thStr == null) {
                thStr = newPath + "/ThWriter.java";
                cuWr.setStorage(new File(thStr).toPath());
                sr.add(cuWr);
            }
            sr.saveAll(new File(newPath).toPath());
        }
    }

    private static void readConfig(BufferedReader cfgReader) {
        try {
            String line;
            while ((line = cfgReader.readLine()) != null) {
                ConfigParser.parse(line);
            }
        } catch (IOException ioException) {
            System.err.println("Can't read config file");
        }
    }

    private static void runVisitor(ProjectRoot pr, ModifierVisitor<?> visitor) {
        for (SourceRoot sr : pr.getSourceRoots()) {
            try {
                for (ParseResult<CompilationUnit> pcu : sr.tryToParse()) {
                    if (pcu.getResult().isPresent()) {
                        CompilationUnit cu = pcu.getResult().get();
                        visitor.visit(cu, null);
                    }
                }
            } catch (IOException ioException) {
                System.err.println("Can't parse source directory");
            }
        }
    }
}


