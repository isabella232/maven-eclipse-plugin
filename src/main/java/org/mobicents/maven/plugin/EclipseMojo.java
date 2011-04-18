/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.maven.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.ObjectUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.mobicents.maven.plugin.eclipse.ClasspathWriter;
import org.mobicents.maven.plugin.eclipse.ProjectWriter;
import org.mobicents.maven.plugin.utils.PathNormalizer;
import org.mobicents.maven.plugin.utils.ProjectUtils;


/**
 * Writes the necessary .classpath and .project files
 * for a new eclipse application.
 *
 * @goal eclipse
 * @phase generate-sources
 * @author Chad Brandon
 * @author Eduardo Martins
 * @author Jean Deruelle
 * @inheritByDefault false
 */

public class EclipseMojo
    extends AbstractMojo
{
    /**
     * @parameter expression="${session}"
     */
    private MavenSession session;

    /**
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    private static final String POM_FILE_NAME = "pom.xml";

    /**
     * Defines the POMs to include when generating the eclipse files.
     *
     * @parameter
     */
    private String[] includes = new String[] {"*/**/" + POM_FILE_NAME};

    /**
     * Defines the POMs to exclude when generating the eclipse files.
     *
     * @parameter
     */
    private String excludePoms;

    /**
     * Artifacts excluded from packaging within the generated archive file. Use
     * groupId, groupId:artifactId or groupId:artifactId:version in nested <exclude/> tags.
     *
     * @parameter
     */
    private Set classpathExcludes;
    
    /**
     * Used to contruct Maven project instances from POMs.
     *
     * @component
     */
    private MavenProjectBuilder projectBuilder;

    /**
     * The name of the variable that will store the maven repository location.
     *
     * @parameter
     */
    private String repositoryVariableName = "M2_REPO";

    /**
     * Artifact factory, needed to download source jars for inclusion in classpath.
     *
     * @component role="org.apache.maven.artifact.factory.ArtifactFactory"
     * @required
     * @readonly
     */
    private ArtifactFactory artifactFactory;

    /**
     * Artifact resolver, needed to download source jars for inclusion in classpath.
     *
     * @component role="org.apache.maven.artifact.resolver.ArtifactResolver"
     * @required
     * @readonly
     */
    private ArtifactResolver artifactResolver;

    /**
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * @component
     */
    private ArtifactMetadataSource artifactMetadataSource;

    /**
     * The artifact types which should be included in the generated Eclipse classpath.
     *
     * @parameter
     */
    private Set classpathArtifactTypes = new LinkedHashSet(Arrays.asList(new String[] {"jar"}));

    /**
     * Whether or not transitive dependencies shall be included in any resources (i.e. .classpath
     * that are generated by this mojo).
     *
     * @parameter expression="${resolveTransitiveDependencies}"
     */
    private boolean resolveTransitiveDependencies = true;

    /**
     * Whether the modules should get a eclipse project too.
     * @parameter expression="${generateProjectsForModules}"
     */
    private boolean generateProjectsForModules = false;
    
    /**
     * Whether the resources directory should be added to the classpath
     * @parameter expression="${includeResourcesDirectory}"
     */
    private boolean includeResourcesDirectory = true;
    
    /**
     * The name for the Eclipse project generated. Defaults to POM's artifactId.
     * @parameter expression="${eclipseProjectName}"
     */
    private String eclipseProjectName;
    
    
    /**
     * Allows non-generated configuration to be "merged" into the generated .classpath file.
     *
     * @parameter
     */
    private String classpathMerge;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public void execute()
        throws MojoExecutionException
        
    {
    	if (!project.isExecutionRoot() && !generateProjectsForModules) {
    		getLog().warn("Skipping module because execution root project didn't configured generateProjectsForModules property as true");
    		return;
    	}
        try
        {
            final MavenProject rootProject = this.getRootProject();
            final ProjectWriter projectWriter = new ProjectWriter(rootProject,
                    this.getLog());
            projectWriter.write(eclipseProjectName != null ? eclipseProjectName : project.getArtifactId());
            final Map originalCompileSourceRoots = this.collectProjectCompileSourceRoots();
            final List projects = this.collectProjects();
            this.processCompileSourceRoots(projects);
            final ClasspathWriter classpathWriter = new ClasspathWriter(rootProject,
                    this.getLog());
            //TODO refactor to pass all arguments as an Options class or this will keep increasing the method signature
            classpathWriter.write(
                projects,
                this.repositoryVariableName,
                this.artifactFactory,
                this.artifactResolver,
                this.localRepository,
                this.artifactMetadataSource,
                this.classpathArtifactTypes,
                this.project.getRemoteArtifactRepositories(),
                this.resolveTransitiveDependencies,
                this.classpathMerge,
                this.classpathExcludes,
                this.includeResourcesDirectory);
            // - reset to the original source roots
            for (final Iterator iterator = projects.iterator(); iterator.hasNext();)
            {
                final MavenProject project = (MavenProject)iterator.next();
                project.getCompileSourceRoots().clear();
                project.getCompileSourceRoots().addAll((List)originalCompileSourceRoots.get(project));
            }
        }
        catch (Throwable throwable)
        {
        	throwable.printStackTrace();
            throw new MojoExecutionException("Error creating eclipse configuration", throwable);
        }
    	
    }

    /**
     * Collects all existing project compile source roots.
     *
     * @return a collection of collections
     */
    private Map collectProjectCompileSourceRoots()
        throws Exception
    {
        final Map sourceRoots = new LinkedHashMap();
        for (final Iterator iterator = this.collectProjects().iterator(); iterator.hasNext();)
        {
            final MavenProject project = (MavenProject)iterator.next();
            sourceRoots.put(project, new ArrayList(project.getCompileSourceRoots()));
        }
        return sourceRoots;
    }

    private List projects = new ArrayList();

    /**
     * Collects all projects from all POMs within the current project.
     *
     * @return all applicable Maven project instances.
     *
     * @throws MojoExecutionException
     */
    private List collectProjects()
        throws Exception
    {
        if (projects.isEmpty())
        {
            final List poms = this.getPoms();
            for (ListIterator iterator = poms.listIterator(); iterator.hasNext();)
            {
                final File pom = (File)iterator.next();
                try
                {
                    // - first attempt to get the existing project from the session
                    final MavenProject project = ProjectUtils.getProject(this.projectBuilder, this.session, pom, this.getLog());
                    if (project != null)
                    {
                        this.getLog().info("found project " + project.getId());
                        projects.add(project);
                    }
                    else
                    {
                        if (this.getLog().isWarnEnabled())
                        {
                            this.getLog().warn("Could not load project from pom: " + pom + " - ignoring");
                        }
                    }
                }
                catch (ProjectBuildingException exception)
                {
                    throw new MojoExecutionException("Error loading " + pom, exception);
                }
            }
        }
        return projects;
    }

    /**
     * Processes the project compile source roots (adds all appropriate ones to the projects)
     * so that they're avialable to the eclipse mojos.
     *
     * @param projects the projects to process.
     * @return the source roots.
     * @throws Exception
     */
    private void processCompileSourceRoots(final List projects)
        throws Exception
    {
        for (final Iterator iterator = projects.iterator(); iterator.hasNext();)
        {
            final MavenProject project = (MavenProject)iterator.next();
            final Set compileSourceRoots = new LinkedHashSet(project.getCompileSourceRoots());
            compileSourceRoots.addAll(this.getExtraSourceDirectories(project));
            final String testSourceDirectory = project.getBuild().getTestSourceDirectory();
            if (testSourceDirectory != null && testSourceDirectory.trim().length() > 0)
            {
                compileSourceRoots.add(testSourceDirectory);
            }
            project.getCompileSourceRoots().clear();
            project.getCompileSourceRoots().addAll(compileSourceRoots);
        }
    }

    /**
     * The artifact id for the multi source plugin.
     */
    private static final String MULTI_SOURCE_PLUGIN_ARTIFACT_ID = "andromda-multi-source-plugin";

    /**
     * Retrieves any additional source directories which are defined within the andromda-multi-source-plugin.
     *
     * @param project the maven project from which to retrieve the extra source directories.
     * @return the list of extra source directories.
     */
    private List getExtraSourceDirectories(final MavenProject project)
    {
        final List sourceDirectories = new ArrayList();
        final Build build = project.getBuild();
        if (build != null)
        {
            final PluginManagement pluginManagement = build.getPluginManagement();
            if (pluginManagement != null && !pluginManagement.getPlugins().isEmpty())
            {
                Plugin multiSourcePlugin = null;
                for (final Iterator iterator = pluginManagement.getPlugins().iterator(); iterator.hasNext();)
                {
                    final Plugin plugin = (Plugin)iterator.next();
                    if (MULTI_SOURCE_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId()))
                    {
                        multiSourcePlugin = plugin;
                        break;
                    }
                }
                final Xpp3Dom configuration = this.getConfiguration(multiSourcePlugin);
                if (configuration != null && configuration.getChildCount() > 0)
                {
                    final Xpp3Dom directories = configuration.getChild(0);
                    if (directories != null)
                    {
                        final int childCount = directories.getChildCount();
                        if (childCount > 0)
                        {
                            final String baseDirectory =
                                PathNormalizer.normalizePath(ObjectUtils.toString(project.getBasedir()) + '/');
                            final Xpp3Dom[] children = directories.getChildren();
                            for (int ctr = 0; ctr < childCount; ctr++)
                            {
                                final Xpp3Dom child = children[ctr];
                                if (child != null)
                                {
                                    String directoryValue = PathNormalizer.normalizePath(child.getValue());
                                    if (directoryValue != null)
                                    {
                                        if (!directoryValue.startsWith(baseDirectory))
                                        {
                                            directoryValue =
                                            	PathNormalizer.normalizePath(baseDirectory + directoryValue.trim());
                                        }
                                        sourceDirectories.add(directoryValue);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return sourceDirectories;
    }

    /**
     * Retrieves the appropriate configuration instance (first tries
     * to get the configuration from the plugin, then tries from the plugin's
     * executions.
     *
     * @param plugin the plugin from which the retrieve the configuration.
     * @return the plugin's configuration, or null if not found.
     */
    private Xpp3Dom getConfiguration(final Plugin plugin)
    {
        Xpp3Dom configuration = null;
        if (plugin != null)
        {
            if (plugin.getConfiguration() != null)
            {
                configuration = (Xpp3Dom)plugin.getConfiguration();
            }
            else
            {
                final List executions = plugin.getExecutions();
                if (executions != null && !executions.isEmpty())
                {
                    // - there should only be one execution so we get the first one
                    final PluginExecution execution = (PluginExecution)plugin.getExecutions().iterator().next();
                    configuration = (Xpp3Dom)execution.getConfiguration();
                }
            }
        }
        return configuration;
    }

    /**
     * Stores the root project.
     */
    private MavenProject rootProject;

    /**
     * Retrieves the root project (i.e. the root parent project)
     * for this project.
     *
     * @return the root project.
     * @throws MojoExecutionException
     */
    private MavenProject getRootProject()
        throws MojoExecutionException, ArtifactResolutionException, ArtifactNotFoundException
    {
        if (this.rootProject == null)
        {
            final MavenProject firstParent = this.project.getParent();
            File rootFile = this.project.getFile();
            /*if (firstParent != null)
            {
                for (this.rootProject = firstParent, rootFile = new File(rootFile.getParentFile().getParentFile(), POM_FILE_NAME);
                     this.rootProject.getParent() != null && this.rootProject.getParent().getFile() != null;
                     this.rootProject = this.rootProject.getParent(), rootFile = new File(rootFile.getParentFile().getParentFile(), POM_FILE_NAME))
                {
                    ;
                }            
                // - if the project has no file defined, use the rootFile
                if (this.rootProject != null && this.rootProject.getFile() == null && rootFile.exists())
                {
                	this.rootProject.setFile(rootFile);
                }
            }
            else
            {*/
                this.rootProject = this.project;
            //}
        }
        return this.rootProject;
       
    }

    /**
     * Retrieves all the POMs for the given project.
     *
     * @return all poms found.
     * @throws MojoExecutionException
     */
    private List getPoms()
        throws Exception
    {
        final DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(this.getRootProject().getBasedir());
        scanner.setIncludes(this.includes);
        scanner.setExcludes(this.excludePoms != null ? excludePoms.split(",") : null);
        scanner.scan();

        List poms = new ArrayList();

        for (int ctr = 0; ctr < scanner.getIncludedFiles().length; ctr++)
        {
            final File file = new File(
                this.getRootProject().getBasedir(),
                scanner.getIncludedFiles()[ctr]);
            if (file.exists())
            {
                poms.add(file);
            }
        }

        return poms;
    }
}