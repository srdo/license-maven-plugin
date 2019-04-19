package org.codehaus.mojo.license.api;

/*
 * #%L
 * License Maven Plugin
 * %%
 * Copyright (C) 2011 CodeLutin, Codehaus, Tony Chemit
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.mojo.license.utils.MojoHelper;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A tool to deal with dependencies of a project.
 *
 * @author tchemit dev@tchemit.fr
 * @since 1.0
 */
@Component( role = DependenciesTool.class, hint = "default" )
public class DependenciesTool
extends AbstractLogEnabled
{

    /**
     * Message used when an invalid expression pattern is found.
     */
    public static final String INVALID_PATTERN_MESSAGE =
        "The pattern specified by expression <%s> seems to be invalid.";

    /**
     * Project builder.
     */
    @Requirement
    private ProjectBuilder mavenProjectBuilder;

    // CHECKSTYLE_OFF: MethodLength
    /**
     * For a given {@code project}, obtain the universe of its dependencies after applying transitivity and
     * filtering rules given in the {@code configuration} object.
     *
     * Result is given in a map where keys are unique artifact id
     *
     * @param dependencies       the project dependencies
     * @param configuration      the configuration
     * @param localRepository    local repository used to resolv dependencies
     * @param remoteRepositories remote repositories used to resolv dependencies
     * @param cache              a optional cache where to keep resolved dependencies
     * @return the map of resolved dependencies indexed by their unique id.
     * @see MavenProjectDependenciesConfigurator
     */
    public SortedMap<String, MavenProject> loadProjectDependencies( ResolvedProjectDependencies artifacts,
                                                                    MavenProjectDependenciesConfigurator configuration,
                                                                    ArtifactRepository localRepository,
                                                                    List<ArtifactRepository> remoteRepositories,
                                                                    SortedMap<String, MavenProject> cache )
    {

        final ArtifactFilters artifactFilters = configuration.getArtifactFilters();

        final boolean excludeTransitiveDependencies = configuration.isExcludeTransitiveDependencies();

        final Set<Artifact> depArtifacts;

        if ( configuration.isIncludeTransitiveDependencies() )
        {
            // All project dependencies
            depArtifacts = artifacts.getAllDependencies();
        }
        else
        {
            // Only direct project dependencies
            depArtifacts = artifacts.getDirectDependencies();
        }

        boolean verbose = configuration.isVerbose();

        SortedMap<String, MavenProject> result = new TreeMap<>();

        Map<String, Artifact> excludeArtifacts = new HashMap<>();
        Map<String, Artifact> includeArtifacts = new HashMap<>();

        SortedMap<String, MavenProject> localCache = new TreeMap<>();
        if ( cache != null )
        {
            localCache.putAll( cache );
        }
        final Logger log = getLogger();

        for ( Artifact artifact : depArtifacts )
        {

            excludeArtifacts.put( artifact.getId(), artifact );

            if ( DefaultThirdPartyTool.LICENSE_DB_TYPE.equals( artifact.getType() ) )
            {
                // the special dependencies for license databases don't count.
                // Note that this will still see transitive deps of a license db; so using the build helper inside of
                // another project to make them will be noisy.
                continue;
            }

            if ( !artifactFilters.isIncluded( artifact ) )
            {
                if ( verbose )
                {
                    log.debug( "Excluding artifact " + artifact );
                }
                continue;
            }

            String id = MojoHelper.getArtifactId( artifact );

            if ( verbose )
            {
                log.info( "detected artifact " + id );
            }

            MavenProject depMavenProject;

            // try to get project from cache
            depMavenProject = localCache.get( id );

            if ( depMavenProject != null )
            {
                if ( verbose )
                {
                    log.info( "add dependency [" + id + "] (from cache)" );
                }
            }
            else
            {
                // build project

                try
                {
                    ProjectBuildingRequest req = new DefaultProjectBuildingRequest()
                            .setRemoteRepositories( remoteRepositories )
                            .setLocalRepository( localRepository );
                    depMavenProject =
                        mavenProjectBuilder.build(artifact, true, req).getProject();
                    depMavenProject.getArtifact().setScope( artifact.getScope() );

                    // In case maven-metadata.xml has different artifactId, groupId or version.
                    if ( !depMavenProject.getGroupId().equals( artifact.getGroupId() ) )
                    {
                        depMavenProject.setGroupId( artifact.getGroupId() );
                        depMavenProject.getArtifact().setGroupId( artifact.getGroupId() );
                    }
                    if ( !depMavenProject.getArtifactId().equals( artifact.getArtifactId() ) )
                    {
                        depMavenProject.setArtifactId( artifact.getArtifactId() );
                        depMavenProject.getArtifact().setArtifactId( artifact.getArtifactId() );
                    }
                    if ( !depMavenProject.getVersion().equals( artifact.getVersion() ) )
                    {
                        depMavenProject.setVersion( artifact.getVersion() );
                        depMavenProject.getArtifact().setVersion( artifact.getVersion() );
                    }
                }
                catch ( ProjectBuildingException e )
                {
                    log.warn( "Unable to obtain POM for artifact : " + artifact, e );
                    continue;
                }

                if ( verbose )
                {
                    log.info( "add dependency [" + id + "]" );
                }

                // store it also in cache
                localCache.put( id, depMavenProject );
            }

            // keep the project
            result.put( id, depMavenProject );

            excludeArtifacts.remove( artifact.getId() );
            includeArtifacts.put( artifact.getId(), artifact );
        }

        // exclude artifacts from the result that contain excluded artifacts in the dependency trail
        if ( excludeTransitiveDependencies )
        {
            for ( Map.Entry<String, Artifact> entry : includeArtifacts.entrySet() )
            {
                List<String> dependencyTrail = entry.getValue().getDependencyTrail();

                boolean remove = false;

                for ( int i = 1; i < dependencyTrail.size() - 1; i++ )
                {
                    if ( excludeArtifacts.containsKey( dependencyTrail.get( i ) ) )
                    {
                        remove = true;
                        break;
                    }
                }

                if ( remove )
                {
                    result.remove( MojoHelper.getArtifactId( entry.getValue() ) );
                }
            }
        }

        if ( cache != null )
        {
            cache.putAll( result );
        }

        return result;
    }
    // CHECKSTYLE_ON: MethodLength
}
