package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.asBinary;
import static com.cloudant.clouseau.OtpUtils.asList;
import static com.cloudant.clouseau.OtpUtils.asOtp;
import static com.cloudant.clouseau.OtpUtils.asString;
import static com.cloudant.clouseau.OtpUtils.atom;
import static com.cloudant.clouseau.OtpUtils.tuple;

import java.io.File;
import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

public class IndexManagerService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private final Timer openTimer;

    public IndexManagerService(final ServerState state) {
        super(state, "main");
        openTimer = Metrics.newTimer(getClass(), "opens");
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws Exception {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            switch (asString(tuple.elementAt(0))) {
            case "open":
                return open(from, tuple);
            case "disk_size":
                return getDiskSize(asString(tuple.elementAt(1)));
            }
        }
        if (request instanceof OtpErlangAtom) {
            switch (asString(request)) {
            case "get_root_dir":
                return tuple(atom("ok"), asBinary(rootDir().getAbsolutePath()));

            case "version":
                return tuple(atom("ok"), asBinary(getClass().getPackage().getImplementationVersion()));
            }
        }
        return null;
    }

    private OtpErlangObject open(final OtpErlangTuple from, final OtpErlangTuple request) throws Exception {
        final OtpErlangPid peer = (OtpErlangPid) request.elementAt(1);
        final OtpErlangBinary path = (OtpErlangBinary) request.elementAt(2);
        final OtpErlangObject analyzerConfig = request.elementAt(3);
        final String strPath = asString(path);

        logger.info(String.format("Opening index at %s", strPath));
        final TimerContext tc = openTimer.time();
        final Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
        final IndexWriter writer = newWriter(path, analyzer);
        final QueryParser qp = new ClouseauQueryParser(LuceneUtils.VERSION, "default", analyzer);
        final IndexService index = new IndexService(state, strPath, writer, qp);
        state.serviceRegistry.register(index);
        index.link(peer);
        tc.stop();
        return tuple(atom("ok"), index.self());
    }

    private OtpErlangObject getDiskSize(final String path) {
        final File indexDir = new File(rootDir(), path);
        final String[] files = indexDir.list();
        long diskSize = 0;
        if (files != null) {
            for (final String file : files) {
                diskSize += new File(indexDir, file).length();
            }
        }
        return tuple(atom("ok"), asList(tuple(atom("disk_size"), asOtp(diskSize))));
    }

    private File rootDir() {
        return new File(state.config.getString("clouseau.dir", "target/indexes"));
    }

    private IndexWriter newWriter(final OtpErlangBinary path, final Analyzer analyzer) throws Exception {
        final Directory dir = newDirectory(new File(rootDir(), asString(path)));
        final IndexWriterConfig writerConfig = new IndexWriterConfig(LuceneUtils.VERSION, analyzer);
        return new IndexWriter(dir, writerConfig);
    }

    private Directory newDirectory(final File path) throws ReflectiveOperationException {
        final String lockClassName = state.config
                .getString("clouseau.lock_class", "org.apache.lucene.store.NativeFSLockFactory");
        final Class<?> lockClass = Class.forName(lockClassName);
        final LockFactory lockFactory = (LockFactory) lockClass.getDeclaredConstructor().newInstance();

        final String dirClassName = state.config
                .getString("clouseau.dir_class", "org.apache.lucene.store.NIOFSDirectory");
        final Class<?> dirClass = Class.forName(dirClassName);
        return (Directory) dirClass.getDeclaredConstructor(File.class, LockFactory.class)
                .newInstance(path, lockFactory);
    }

}
