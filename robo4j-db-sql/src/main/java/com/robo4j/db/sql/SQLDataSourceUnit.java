/*
 * Copyright (c) 2014, 2017, Marcus Hirt, Miroslav Wengner
 *
 * Robo4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Robo4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Robo4J. If not, see <http://www.gnu.org/licenses/>.
 */

package com.robo4j.db.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Root;
import javax.persistence.spi.PersistenceUnitInfo;

import com.robo4j.db.sql.model.Robo4JSystem;
import com.robo4j.db.sql.model.RoboEntity;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.PersistenceUnitInfoDescriptor;
import org.hibernate.jpa.boot.spi.EntityManagerFactoryBuilder;
import org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor;

import com.robo4j.core.AttributeDescriptor;
import com.robo4j.core.ConfigurationException;
import com.robo4j.core.DefaultAttributeDescriptor;
import com.robo4j.core.LifecycleState;
import com.robo4j.core.RoboContext;
import com.robo4j.core.RoboUnit;
import com.robo4j.core.client.util.RoboClassLoader;
import com.robo4j.core.configuration.Configuration;
import com.robo4j.core.httpunit.Constants;
import com.robo4j.db.sql.jpa.PersistenceDescriptorProvider;
import com.robo4j.db.sql.model.Robo4JUnit;
import com.robo4j.db.sql.support.DataSourceContext;
import com.robo4j.db.sql.support.DataSourceProxy;

/**
 * @author Marcus Hirt (@hirt)
 * @author Miro Wengner (@miragemiko)
 */
public class SQLDataSourceUnit extends RoboUnit<RoboEntity> {

	private static final String ATTRIBUTE_ROBO_UNIT_NAME = "units";
	private static final Collection<AttributeDescriptor<?>> KNOWN_ATTRIBUTES = Collections
			.singleton(DefaultAttributeDescriptor.create(List.class, ATTRIBUTE_ROBO_UNIT_NAME));

	private static final String PERSISTENCE_UNIT = "persistenceUnit";
	private static final String PACKAGES = "packages";
	private String persistenceUnit;
	private String[] packages;
	private DataSourceContext dataSourceContext;
	private EntityManagerFactory emf;

	public SQLDataSourceUnit(RoboContext context, String id) {
		super(RoboEntity.class, context, id);
	}

	@Override
	protected void onInitialization(Configuration configuration) throws ConfigurationException {
		persistenceUnit = configuration.getString(PERSISTENCE_UNIT, null);
		if (persistenceUnit == null) {
			throw ConfigurationException.createMissingConfigNameException(PERSISTENCE_UNIT);
		}

		String tmpPackages = configuration.getString(PACKAGES, null);
		if (tmpPackages == null) {
			throw ConfigurationException.createMissingConfigNameException(PACKAGES);
		}
		//@formatter:off
		packages = tmpPackages.split(Constants.UTF8_COMMA);
		//@formatter:on
	}

	@Override
	public void onMessage(RoboEntity message) {
		EntityManager em = dataSourceContext.getEntityManager(message.getClass());
		em.getTransaction().begin();
		em.persist(message);
		em.getTransaction().commit();
	}

	@Override
	public void start() {
		setState(LifecycleState.STARTING);
		PersistenceUnitInfo persistenceUnitInfo = new PersistenceDescriptorProvider()
				.getH2(RoboClassLoader.getInstance().getClassLoader(), packages);
		PersistenceUnitDescriptor persistenceUnitDescriptor = new PersistenceUnitInfoDescriptor(persistenceUnitInfo);
		EntityManagerFactoryBuilder builder = new EntityManagerFactoryBuilderImpl(persistenceUnitDescriptor,
				new HashMap());

		emf = builder.build();
		dataSourceContext = new DataSourceProxy(Collections.singleton(emf.createEntityManager()));
		setState(LifecycleState.STARTED);
	}

	@Override
	public void stop() {
		setState(LifecycleState.STOPPING);
		setState(LifecycleState.STOPPED);
	}

	@Override
	public void shutdown() {
		setState(LifecycleState.SHUTTING_DOWN);
		emf.close();
		setState(LifecycleState.SHUTDOWN);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <R> R onGetAttribute(AttributeDescriptor<R> descriptor) {
		if (descriptor.getAttributeName().equals(ATTRIBUTE_ROBO_UNIT_NAME)
				&& descriptor.getAttributeType() == List.class) {
			EntityManager em = dataSourceContext.getEntityManager(Robo4JUnit.class);
			CriteriaBuilder cb1 = em.getCriteriaBuilder();
			CriteriaQuery<Robo4JUnit> q1 = cb1.createQuery(Robo4JUnit.class);
			Root<Robo4JUnit> rs1 = q1.from(Robo4JUnit.class);
			CriteriaQuery c1 = q1.select(rs1);

			CriteriaBuilder cb2 = em.getCriteriaBuilder();
			CriteriaQuery<Robo4JSystem> q2 = cb2.createQuery(Robo4JSystem.class);
			Root<Robo4JSystem> rs2 = q2.from(Robo4JSystem.class);
			CriteriaQuery c2 = q2.select(rs2);

			TypedQuery<Robo4JUnit> query1 = em.createQuery(c1);
			TypedQuery<Robo4JSystem> query2 = em.createQuery(c2);

			List<Object> result = new ArrayList<>();
			result.addAll(query1.getResultList());
			result.addAll(query2.getResultList());
			return (R) result;

		}
		return super.onGetAttribute(descriptor);
	}

	@Override
	public Collection<AttributeDescriptor<?>> getKnownAttributes() {
		return KNOWN_ATTRIBUTES;
	}
}