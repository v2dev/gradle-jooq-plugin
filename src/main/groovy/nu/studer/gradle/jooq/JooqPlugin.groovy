/**
 Copyright 2014 Etienne Studer

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package nu.studer.gradle.jooq

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.SourceSet
import org.jooq.util.jaxb.Generator
import org.jooq.util.jaxb.Target

/**
 * Plugin that extends the java-base plugin and registers a {@link JooqTask} for each defined jOOQ configuration.
 * Each task generates the jOOQ source code from the configured database. The tasks properly participate in the Gradle
 * up-to-date checks. The tasks are wired as dependencies of the compilation tasks of the JavaBasePlugin plugin.
 */
@SuppressWarnings("GroovyUnusedDeclaration")
class JooqPlugin implements Plugin<Project> {

    private static final String JOOQ_EXTENSION_NAME = "jooq"

    Project project
    JooqExtension extension
    Configuration jooqRuntime

    public void apply(Project project) {
        this.project = project

        project.plugins.apply(JavaBasePlugin.class)
        addJooqExtension(project)
        addJooqConfiguration(project)
        forceJooqVersionAndEdition(project)
    }

    /**
     * Adds the DSL extensions that allows the user to configure key aspects of
     * this plugin.
     */
    private void addJooqExtension(Project project) {
        def whenConfigurationAdded = { JooqConfiguration jooqConfiguration ->
            def jooqTask = createJooqTask(jooqConfiguration)
            cleanJooqSourcesWhenRunningCleanTak(jooqTask)
            configureDefaultOutput(jooqConfiguration)
            configureSourceSet(jooqConfiguration)
        }

        extension = project.extensions.create(JOOQ_EXTENSION_NAME, JooqExtension.class, whenConfigurationAdded, JOOQ_EXTENSION_NAME)
    }

    /**
     * Adds the configuration that holds the classpath to use for invoking jOOQ.
     * Users can add their JDBC drivers or any generator extensions they might have.
     */
    private void addJooqConfiguration(Project project) {
        jooqRuntime = project.configurations.create("jooqRuntime")
        jooqRuntime.setDescription("The classpath used to invoke the jOOQ generator. Add your JDBC drivers or generator extensions here.")
        project.dependencies.add(jooqRuntime.name, "org.jooq:jooq-codegen")
    }

    /**
     * Forces the jOOQ version and edition selected by the user throughout all
     * dependency configurations.
     */
    private void forceJooqVersionAndEdition(Project project) {
        def jooqGroupIds = JooqEdition.values().collect { it.groupId }.toSet()
        project.configurations.all {
            resolutionStrategy.eachDependency { details ->
                def requested = details.requested
                if (jooqGroupIds.contains(requested.group) && requested.name.startsWith('jooq')) {
                    details.useTarget("$extension.edition.groupId:$requested.name:$extension.version")
                }
            }
        }
    }

    /**
     * Adds the task that runs the jOOQ code generator in a separate process.
     */
    private Task createJooqTask(JooqConfiguration jooqConfiguration) {
        JooqTask jooqTask = project.tasks.create(jooqConfiguration.jooqTaskName, JooqTask.class)
        jooqTask.description = "Generates the jOOQ sources from the '$jooqConfiguration.name' jOOQ configuration."
        jooqTask.group = "jOOQ"
        jooqTask.configuration = jooqConfiguration.configuration
        jooqTask.jooqClasspath = jooqRuntime
        jooqTask
    }

    /**
     * Wires the task that deletes the jOOQ sources as a dependency of the pre-existing 'clean' task, and
     * makes sure the task execution ordering is such that the deletion happens before regenerating the jOOQ sources.
     */
    private void cleanJooqSourcesWhenRunningCleanTak(Task task) {
        String cleanJooqSources = "clean" + task.name.capitalize()
        project.getTasks().getByName(BasePlugin.CLEAN_TASK_NAME).dependsOn(cleanJooqSources)
        task.mustRunAfter(cleanJooqSources)
    }

    /**
     * Configures a sensible default output directory.
     */
    private void configureDefaultOutput(JooqConfiguration jooqConfiguration) {
        String outputDirectoryName = "${project.buildDir}/generated-src/jooq/$jooqConfiguration.name"
        jooqConfiguration.configuration.withGenerator(new Generator().withTarget(new Target().withDirectory(outputDirectoryName)))
    }

    /**
     * Ensures the Java compiler will pick up the generated sources.
     */
    private void configureSourceSet(JooqConfiguration jooqConfiguration) {
        SourceSet sourceSet = jooqConfiguration.sourceSet
        sourceSet.java.srcDir { jooqConfiguration.configuration.generator.target.directory }
        project.tasks.getByName(sourceSet.compileJavaTaskName).dependsOn jooqConfiguration.jooqTaskName
    }

}
