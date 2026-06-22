package lean4j.lir;

import java.math.BigInteger;
import java.util.*;

import lean4j.lir.LirIR.*;

/** Loads Lean IR from the lean4j_ir.json format produced by LeanIRExport.lean. */
public final class LirLoader {

    private LirLoader() {}

    public static Map<String, Decl> load(String json) {
        Json root = Json.parse(json);
        List<Json> declList = root.get("decls").arr();
        Map<String, Decl> decls = new LinkedHashMap<>();
        for (Json d : declList) {
            Decl decl = parseDecl(d);
            decls.put(decl.name(), decl);
        }
        return decls;
    }

    /** Parse the "inits" map: module-level global name → its initializer function. */
    public static Map<String, String> loadInits(String json) {
        Json root = Json.parse(json);
        Map<String, String> inits = new LinkedHashMap<>();
        if (root.has("inits")) {
            for (Json e : root.get("inits").arr()) {
                inits.put(e.get("global").str(), e.get("initFn").str());
            }
        }
        return inits;
    }

    // ── Decl ──

    private static Decl parseDecl(Json j) {
        String tag = j.get("tag").str();
        String name = j.get("name").str();
        Param[] params = parseParams(j.get("params").arr());
        String retTy = j.get("retTy").str();
        return switch (tag) {
            case "fdecl"  -> new Decl.FDecl(name, params, retTy, parseBody(j.get("body")));
            case "extern" -> new Decl.Extern(name, params, retTy);
            default -> throw new IllegalArgumentException("Unknown decl tag: " + tag);
        };
    }

    // ── Params ──

    private static Param[] parseParams(List<Json> list) {
        return list.stream().map(LirLoader::parseParam).toArray(Param[]::new);
    }

    private static Param parseParam(Json j) {
        return new Param((int) j.get("id").num(), j.get("borrow").bool(), j.get("ty").str());
    }

    // ── CtorInfo ──

    private static CtorInfo parseCtorInfo(Json j) {
        return new CtorInfo(
            j.get("name").str(),
            (int) j.get("cidx").num(),
            (int) j.get("size").num(),
            (int) j.get("usize").num(),
            (int) j.get("ssize").num()
        );
    }

    // ── Args ──

    private static Arg[] parseArgs(List<Json> list) {
        return list.stream().map(LirLoader::parseArg).toArray(Arg[]::new);
    }

    private static Arg parseArg(Json j) {
        return switch (j.get("tag").str()) {
            case "var"    -> new Arg.Var((int) j.get("id").num());
            case "erased" -> new Arg.Erased();
            default -> throw new IllegalArgumentException("Unknown arg: " + j.get("tag").str());
        };
    }

    // ── Expr ──

    private static Expr parseExpr(Json j) {
        String tag = j.get("tag").str();
        return switch (tag) {
            case "fap"       -> new Expr.Fap(j.get("fn").str(), parseArgs(j.get("args").arr()));
            case "pap"       -> new Expr.Pap(j.get("fn").str(), parseArgs(j.get("args").arr()));
            case "ap"        -> new Expr.Ap((int) j.get("id").num(), parseArgs(j.get("args").arr()));
            case "ctor"      -> new Expr.Ctor(parseCtorInfo(j.get("info")), parseArgs(j.get("args").arr()));
            case "proj"      -> new Expr.Proj((int) j.get("i").num(), (int) j.get("id").num());
            case "uproj"     -> new Expr.UProj((int) j.get("i").num(), (int) j.get("id").num());
            case "sproj"     -> new Expr.SProj((int) j.get("n").num(), (int) j.get("o").num(), (int) j.get("id").num());
            case "reset"     -> new Expr.Reset((int) j.get("n").num(), (int) j.get("id").num());
            case "reuse"     -> new Expr.Reuse((int) j.get("id").num(), parseCtorInfo(j.get("info")), parseArgs(j.get("args").arr()));
            case "box"       -> new Expr.Box(j.get("ty").str(), (int) j.get("id").num());
            case "unbox"     -> new Expr.Unbox((int) j.get("id").num());
            case "lit"       -> parseLit(j.get("val"));
            case "isShared"  -> new Expr.IsShared((int) j.get("id").num());
            default -> throw new IllegalArgumentException("Unknown expr tag: " + tag);
        };
    }

    private static Expr.Lit parseLit(Json j) {
        String tag = j.get("tag").str();
        return switch (tag) {
            case "str" -> new Expr.Lit(j.get("val").str());
            case "num" -> {
                String s = j.get("val").str();
                try {
                    yield new Expr.Lit(Long.parseUnsignedLong(s));
                } catch (NumberFormatException e) {
                    yield new Expr.Lit(new BigInteger(s));
                }
            }
            default -> throw new IllegalArgumentException("Unknown lit tag: " + tag);
        };
    }

    // ── Body ──

    private static Body parseBody(Json j) {
        String tag = j.get("tag").str();
        return switch (tag) {
            case "vdecl"  -> new Body.VDecl(
                                (int) j.get("id").num(),
                                j.get("ty").str(),
                                parseExpr(j.get("expr")),
                                parseBody(j.get("cont")));
            case "jdecl"  -> new Body.JDecl(
                                (int) j.get("jid").num(),
                                parseParams(j.get("params").arr()),
                                parseBody(j.get("body")),
                                parseBody(j.get("cont")));
            case "set"    -> new Body.Set(
                                (int) j.get("id").num(),
                                (int) j.get("i").num(),
                                parseArg(j.get("arg")),
                                parseBody(j.get("cont")));
            case "setTag" -> new Body.SetTag(
                                (int) j.get("id").num(),
                                (int) j.get("cidx").num(),
                                parseBody(j.get("cont")));
            case "uset"   -> new Body.USet(
                                (int) j.get("id").num(),
                                (int) j.get("i").num(),
                                (int) j.get("yid").num(),
                                parseBody(j.get("cont")));
            case "sset"   -> new Body.SSet(
                                (int) j.get("id").num(),
                                (int) j.get("i").num(),
                                (int) j.get("o").num(),
                                (int) j.get("yid").num(),
                                j.get("ty").str(),
                                parseBody(j.get("cont")));
            case "inc"    -> new Body.Inc((int) j.get("id").num(), parseBody(j.get("cont")));
            case "dec"    -> new Body.Dec((int) j.get("id").num(), parseBody(j.get("cont")));
            case "del"    -> new Body.Del((int) j.get("id").num(), parseBody(j.get("cont")));
            case "case"   -> new Body.Case(
                                j.get("tid").str(),
                                (int) j.get("id").num(),
                                j.get("ty").str(),
                                parseAlts(j.get("alts").arr()));
            case "ret"    -> new Body.Ret(parseArg(j.get("arg")));
            case "jmp"    -> new Body.Jmp((int) j.get("jid").num(), parseArgs(j.get("args").arr()));
            case "unreachable" -> new Body.Unreachable();
            default -> throw new IllegalArgumentException("Unknown body tag: " + tag);
        };
    }

    // ── Alts ──

    private static Alt[] parseAlts(List<Json> list) {
        return list.stream().map(LirLoader::parseAlt).toArray(Alt[]::new);
    }

    private static Alt parseAlt(Json j) {
        String tag = j.get("tag").str();
        Body body = parseBody(j.get("body"));
        return switch (tag) {
            case "ctor"    -> new Alt.Ctor(parseCtorInfo(j.get("info")), body);
            case "default" -> new Alt.Default(body);
            default -> throw new IllegalArgumentException("Unknown alt tag: " + tag);
        };
    }
}
