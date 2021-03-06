/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.gradle;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.ConsoleLogFilter;
import hudson.model.*;
import hudson.tools.ToolInstallation;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.*;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.sql.CallableStatement;
import java.util.HashMap;
import java.util.List;

/**
 * The execution of the {@link WithGradle} pipeline step. Configures the ConsoleAnnotator for a Gradle build and, if
 * specified, configures Gradle and Java for the build.
 *
 * @author Alex Johnson
 */
public class WithGradleExecution extends StepExecution {

    /** The Step and it's required context */
    private transient WithGradle step;
    private transient FilePath workspace;
    private transient Run run;
    private transient TaskListener listener;
    private transient EnvVars envVars;

    private BodyExecution block;

    public WithGradleExecution (StepContext context, WithGradle step) throws IOException, InterruptedException {
        super(context);
        this.step = step;

        workspace = context.get(FilePath.class);
        run = context.get(Run.class);
        listener = context.get(TaskListener.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean start() throws Exception {
        listener.getLogger().printf("[WithGradle] Execution begin");
        envVars = new EnvVars();

        String gradleName = step.getGradle();
        if (gradleName != null) {
            GradleInstallation gradleInstallation = null;
            GradleInstallation[] installations = ToolInstallation.all().get(GradleInstallation.DescriptorImpl.class).getInstallations();
            for (GradleInstallation i : installations) {
                if (i.getName().equals(gradleName)) {
                    gradleInstallation = i;
                }
            }
            if (gradleInstallation == null) {
                listener.getLogger().printf("[WithGradle] Gradle Installation '%s' not found. Defaulting to system installation. %n", gradleName);
            } else {
                listener.getLogger().printf("[WithGradle] Gradle Installation found. Using '%s' %n", gradleInstallation.getName());
                envVars.put("GRADLE_HOME", gradleInstallation.getHome());
            }
            // set build failure and return if incorrect
        }

        String javaName = step.getJdk();
        if (javaName != null) {
            JDK javaInstallation = Jenkins.getActiveInstance().getJDK(javaName);
            if (javaInstallation == null) {
                listener.getLogger().printf("[WithGradle] Java Installation '%s' not found. Defaulting to system installation. %n", javaName);
            } else {
                listener.getLogger().printf("[WithGradle] Java Installation found. Using '%s' %n", javaInstallation.getName());
                envVars.put("JAVA_HOME", javaInstallation.getHome());
            }
            // set build failure and return if incorrect
        }

        /*
            Possible TODO:
             - specify settings.gradle
             - specify artifact repository
             - other Gradle options
                Gradle has a lot of options that can be specified via command line or gradle.properties, imo the only
                ones that need to be taken care of are the ones that need to be added to the build environment
        */

        ConsoleLogFilter annotator = BodyInvoker.mergeConsoleLogFilters(getContext().get(ConsoleLogFilter.class), new GradleConsoleFilter());
        EnvironmentExpander expander = EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new GradleExpander(envVars));

        if (getContext().hasBody()) {
           block = getContext().newBodyInvoker().withContexts(annotator, expander).withCallback(new CallbackImpl()).start();
        } else {
            throw new AbortException(String.format("[WithGradle][ERROR] No body to invoke. Make sure your withGradle " +
                    "pipeline step includes a code block that runs your Gradle build. example: %n> withGradle (){%n>     sh " +
                    "'gradle myTask'%n> }%n"));
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(@Nonnull Throwable cause) throws Exception {

    }

    /**
     * Wraps {@link GradleConsoleAnnotator} in a {@link ConsoleLogFilter} so it can be merged with the existing
     * log filter.
     */
    private static class GradleConsoleFilter extends ConsoleLogFilter implements Serializable {

        private static final long serialVersionUID = 1;

        public GradleConsoleFilter() {
        }

        /**
         * Creates a {@link GradleConsoleAnnotator} for an {@link OutputStream}
         *
         * @param build this is ignored
         * @param out the {@link OutputStream} to annotate
         * @return the {@link GradleConsoleAnnotator} for the OutputStream
         */
        @Override
        public OutputStream decorateLogger(AbstractBuild build, final OutputStream out) {
            return new GradleConsoleAnnotator(out, Charset.forName("UTF-8"));
        }
    }

    /**
     * Overrides the existing environment with the pipeline Gradle settings
     */
    private static final class GradleExpander extends EnvironmentExpander {

        private static final long serialVersionUID = 1;

        private final EnvVars gradleEnv;

        private GradleExpander(EnvVars env) {
            this.gradleEnv = new EnvVars();
            gradleEnv.putAll(env);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void expand(EnvVars env) throws IOException, InterruptedException {
            env.overrideAll(gradleEnv);
        }
    }

    /**
     * The Callback for the end of the withGradle block
     */
    private static class CallbackImpl extends BodyExecutionCallback.TailCall {

        public CallbackImpl() {
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            /*
               This checks each line of the log for the Gradle build failure message, there is probably a better way to do this.

               This is called immediately after the code block has been executed which is async with the Gradle build. This
               will almost always set the build status to true because the Gradle build will not have completed when the
               code block body has finished executing.
            */
            boolean finished = false;
            while (!finished) {
                List<String> log = context.get(Run.class).getLog(100);
                for (String s : log) {
                    if (s.contains("BUILD") || s.contains("ERROR")) {
                        finished = true;
                        break;
                    }
                }
                Thread.sleep(100);
            }

            List<String> log = context.get(Run.class).getLog(100);
            for (String s : log) {
                if (s.contains("BUILD FAILED")) {
                    context.onFailure(new Failure("Gradle build was marked as FAILURE"));
                    return;
                } else if (s.contains("ERROR")) {
                    context.onFailure(new Failure("Gradle threw an ERROR"));
                    return;
                }
            }
            context.onSuccess(Result.SUCCESS);
        }

        private static final long serialVersionUID = 1L;
    }

}
