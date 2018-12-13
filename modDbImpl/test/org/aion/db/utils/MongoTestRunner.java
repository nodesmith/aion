package org.aion.db.utils;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.List;
import org.aion.db.impl.DatabaseTestUtils;


import static org.junit.Assert.fail;

/**
 * Helper class for spinning up a MongoDB instance to be used for unit tests.
 */
public class MongoTestRunner implements AutoCloseable {

    private int port;
    private Process runningMongoServer;
    private File databaseFilesDir;

    private static class Holder {
        static final MongoTestRunner INSTANCE = new MongoTestRunner();
    }

    public static MongoTestRunner inst() {
        return Holder.INSTANCE;
    }

    private MongoTestRunner() {
        this.port = DatabaseTestUtils.findOpenPort();

        // Create a temp directory to store our db files in
        this.databaseFilesDir = FileUtils.createTempDir("mongodb");
        this.databaseFilesDir.mkdirs();

        // Find the path to the actual mongo db executable
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("mongo/bin/mongod").getFile());
        String mongodPath = file.getAbsolutePath();


        try {
            // First we need to just start the mongo database
            List<String> commands = List.of(
                mongodPath,
                "--port",
                Integer.toString(this.port),
                "--dbpath",
                databaseFilesDir.getAbsolutePath(),
                "--replSet",
                String.format("rs%d", System.currentTimeMillis()),
                "--noauth",
                "--nojournal",
                "--quiet",
                "--logpath",
                new File(databaseFilesDir, "log.log").getAbsolutePath()
            );

            this.runningMongoServer = new ProcessBuilder(commands)
                .redirectError(Redirect.INHERIT)
                .start();

            this.runningMongoServer.onExit().thenAccept(p -> {
                System.err.println("Unexpected mongo database shutdown with code " + p.exitValue());
                fail("Mongo database crashed");
            });

            // Next we run a command to initialize the mongo server's replicas set and admin accounts
            List<String> initializationCommands = List.of(
                new File(classLoader.getResource("mongo/bin/mongo").getFile()).getAbsolutePath(),
                "--host",
                "localhost",
                "--port",
                Integer.toString(this.port),
                new File(classLoader.getResource("mongo/initScript.js").getFile()).getAbsolutePath()
            );

            tryInitializeDb(initializationCommands, 30, 100);

        } catch (IOException e) {
            e.printStackTrace();
            fail("Exception thrown while starting Mongo");
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Exception thrown while starting Mongo");
        }

        // Add a shutdown hook to kill the Mongo server when the process dies
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close();
            } catch (Exception e) {
                e.printStackTrace();
                fail("Failed to close MongoDB connection");
            }
        }));
    }

    /**
     * Helper method to run some initialization command on Mongo with some retry logic if the command fails. Since it's
     * not determinate how long starting the database will take, we need this retry logic.
     * @param initializationCommands The command to actually run
     * @param retriesRemaining How many more times to retry the command if it fails
     * @param pauseTimeMillis How long to pause between retries
     * @throws InterruptedException Thrown when the thread gets interrupted trying to sleep.
     */
    private void tryInitializeDb(List<String> initializationCommands, int retriesRemaining, long pauseTimeMillis)
        throws InterruptedException {

        int exitCode = -1;
        Exception exception = null;
        try {
            exitCode = new ProcessBuilder(initializationCommands)
                .redirectError(Redirect.INHERIT)
                .start()
                .waitFor();
        } catch (Exception e) {
            exception = e;
        }

        if (exception != null || exitCode != 0) {
            // This is the case that the command didn't work
            if (retriesRemaining == 0) {
                // We're out of retries, we should fail
                if (exception != null) {
                    exception.printStackTrace();
                }

                fail("Failed to initialize MongoDB, no retries remaining. Exit code was: " + Integer.toString(exitCode));
            } else {
                Thread.sleep(pauseTimeMillis);
                tryInitializeDb(initializationCommands, retriesRemaining - 1, pauseTimeMillis);
            }
        }
    }

    /**
     * Returns the connection string to be used to connect to the started Mongo instance
     * @return The connection string.
     */
    public String getConnectionString() {
        return String.format("mongodb://localhost:%d", this.port);
    }

    @Override
    public void close() throws Exception {
        if (this.runningMongoServer != null) {
            this.runningMongoServer.destroy();
            FileUtils.deleteRecursively(this.databaseFilesDir);
        }

        this.runningMongoServer = null;
    }
}
