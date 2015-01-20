/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.usergrid.persistence.cassandra;


import org.apache.commons.lang3.RandomStringUtils;
import org.apache.usergrid.AbstractCoreIT;
import org.apache.usergrid.cassandra.CassandraResource;
import org.apache.usergrid.cassandra.Concurrent;
import org.apache.usergrid.persistence.*;
import org.apache.usergrid.persistence.cassandra.util.TraceTag;
import org.apache.usergrid.persistence.cassandra.util.TraceTagManager;
import org.apache.usergrid.persistence.cassandra.util.TraceTagReporter;
import org.apache.usergrid.persistence.index.impl.ElasticSearchResource;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.Assert.*;


@Concurrent()
public class EntityManagerFactoryImplIT extends AbstractCoreIT {

    @SuppressWarnings("PointlessBooleanExpression")
    public static final boolean USE_DEFAULT_DOMAIN = !CassandraService.USE_VIRTUAL_KEYSPACES;

    private static final Logger logger = LoggerFactory.getLogger( EntityManagerFactoryImplIT.class );


    @ClassRule
    public static CassandraResource cassandraResource = CassandraResource.newWithAvailablePorts();

    @ClassRule
    public static ElasticSearchResource elasticSearchResource = new ElasticSearchResource();


    public EntityManagerFactoryImplIT() {
        emf = cassandraResource.getBean( EntityManagerFactory.class );
    }


    @BeforeClass
    public static void setup() throws Exception {
        logger.info( "setup" );
    }


    @AfterClass
    public static void teardown() throws Exception {
        logger.info( "teardown" );
    }


    EntityManagerFactory emf;
    TraceTagManager traceTagManager;
    TraceTagReporter traceTagReporter;


    public UUID createApplication( String organizationName, String applicationName ) throws Exception {
        if ( USE_DEFAULT_DOMAIN ) {
            return emf.getDefaultAppId();
        }
        return emf.createApplication( organizationName, applicationName );
    }


    @Before
    public void initTracing() {
        traceTagManager = cassandraResource.getBean(
                "traceTagManager", TraceTagManager.class );
        traceTagReporter = cassandraResource.getBean(
                "traceTagReporter", TraceTagReporter.class );
    }


    @Test
    public void testDeleteApplication() throws Exception {

        String rand = RandomStringUtils.randomAlphabetic(20);

        // create an application with a collection and an entity

        UUID applicationId = setup.createApplication( "test-org-" + rand, "test-app-" + rand );

        EntityManager em = setup.getEmf().getEntityManager( applicationId );

        Map<String, Object> properties1 = new LinkedHashMap<String, Object>();
        properties1.put( "Name", "12 Angry Men" );
        properties1.put( "Year", 1957 );
        Entity film1 = em.create("film", properties1);

        Map<String, Object> properties2 = new LinkedHashMap<String, Object>();
        properties2.put( "Name", "Reservoir Dogs" );
        properties2.put( "Year", 1992 );
        Entity film2 = em.create( "film", properties2 );

        em.refreshIndex();

        // delete the application

        setup.getEmf().deleteApplication( applicationId );

        // attempt to get entities in application's collections in various ways should all fail

        assertNull( setup.getEmf().lookupApplication("test-app-" + rand) );

        Map<String, UUID> appMap = setup.getEmf().getApplications();
        for ( String appName : appMap.keySet() ) {
            UUID appId = appMap.get( appName );
            assertNotEquals( appId, applicationId );
            assertNotEquals( appName, "test-app-" + rand );
        }

    }


    @Test
    public void testCreateAndGet() throws Exception {
        TraceTag traceTag = traceTagManager.create( "testCreateAndGet" );
        traceTagManager.attach( traceTag );
        logger.info( "EntityDaoTest.testCreateAndGet" );

        UUID applicationId = createApplication( "EntityManagerFactoryImplIT", "testCreateAndGet"
                + RandomStringUtils.randomAlphabetic(20)  );
        logger.info( "Application id " + applicationId );

        EntityManager em = emf.getEntityManager( applicationId );

        int i;
        List<Entity> things = new ArrayList<Entity>();
        for ( i = 0; i < 10; i++ ) {
            Map<String, Object> properties = new LinkedHashMap<String, Object>();
            properties.put( "name", "thing" + i );

            Entity thing = em.create( "thing", properties );
            assertNotNull( "thing should not be null", thing );
            assertFalse( "thing id not valid", thing.getUuid().equals( new UUID( 0, 0 ) ) );
            assertEquals( "name not expected value", "thing" + i, thing.getProperty( "name" ) );

            things.add( thing );
        }
        assertEquals( "should be ten entities", 10, things.size() );

        i = 0;
        for ( Entity entity : things ) {

            Entity thing = em.get( new SimpleEntityRef("thing", entity.getUuid()));
            assertNotNull( "thing should not be null", thing );
            assertFalse( "thing id not valid", thing.getUuid().equals( new UUID( 0, 0 ) ) );
            assertEquals( "name not expected value", "thing" + i, thing.getProperty( "name" ) );

            i++;
        }

        List<UUID> ids = new ArrayList<UUID>();
        for ( Entity entity : things ) {
            ids.add( entity.getUuid() );

            Entity en = em.get( new SimpleEntityRef("thing", entity.getUuid()));
            String type = en.getType();
            assertEquals( "type not expected value", "thing", type );

            Object property = en.getProperty( "name" );
            assertNotNull( "thing name property should not be null", property );
            assertTrue( "thing name should start with \"thing\"", property.toString().startsWith( "thing" ) );

            Map<String, Object> properties = en.getProperties();
            assertEquals( "number of properties wrong", 5, properties.size() );
        }

        i = 0;
        Results results = em.getEntities( ids, "thing" );
        for ( Entity thing : results ) {
            assertNotNull( "thing should not be null", thing );

            assertFalse( "thing id not valid", thing.getUuid().equals( new UUID( 0, 0 ) ) );

            assertEquals( "wrong type", "thing", thing.getType() );

            assertNotNull( "thing name should not be null", thing.getProperty( "name" ) );
            String name = thing.getProperty( "name" ).toString();
            assertEquals( "unexpected name", "thing" + i, name );

            i++;
        }

        assertEquals( "entities unfound entity name count incorrect", 10, i );

		/*
         * List<UUID> entities = emf.findEntityIds(applicationId, "thing", null,
		 * null, 100); assertNotNull("entities list should not be null",
		 * entities); assertEquals("entities count incorrect", 10,
		 * entities.size());
		 */
        traceTagReporter.report( traceTagManager.detach() );
    }
}
