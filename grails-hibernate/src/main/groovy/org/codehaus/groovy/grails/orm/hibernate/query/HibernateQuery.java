/* Copyright (C) 2011 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.groovy.grails.orm.hibernate.query;

import grails.orm.HibernateCriteriaBuilder;
import grails.orm.RlikeExpression;

import java.lang.reflect.Field;
import java.util.*;

import org.codehaus.groovy.grails.orm.hibernate.HibernateSession;
import org.grails.datastore.gorm.query.criteria.DetachedAssociationCriteria;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.Association;
import org.grails.datastore.mapping.query.AssociationQuery;
import org.grails.datastore.mapping.query.Query;
import org.grails.datastore.mapping.query.api.QueryableCriteria;
import org.grails.datastore.mapping.query.criteria.FunctionCallingCriterion;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.*;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.function.SQLFunction;
import org.hibernate.engine.SessionFactoryImplementor;
import org.hibernate.persister.entity.PropertyMapping;
import org.hibernate.type.BasicType;
import org.hibernate.type.TypeResolver;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.util.ReflectionUtils;

/**
 * Bridges the Query API with the Hibernate Criteria API
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@SuppressWarnings("rawtypes")
public class HibernateQuery extends Query {



    @SuppressWarnings("hiding")
    private Criteria criteria;
    private HibernateQuery.HibernateProjectionList hibernateProjectionList = null;
    private String alias;
    private int aliasCount;
    private Map<String, CriteriaAndAlias> createdAssociationPaths = new HashMap<String, CriteriaAndAlias> ();
    private static final String ALIAS = "_alias";
    private Field opField;


    public HibernateQuery(Criteria criteria, HibernateSession session, PersistentEntity entity) {
        super(session, entity);
        this.criteria = criteria;
        this.opField = ReflectionUtils.findField(SimpleExpression.class, "op");
        ReflectionUtils.makeAccessible(opField);

    }

    public HibernateQuery(Criteria subCriteria, HibernateSession session, PersistentEntity associatedEntity, String newAlias) {
        this(subCriteria, session, associatedEntity);
        this.alias = newAlias;
    }

    @Override
    public Query isEmpty(String property) {
        org.hibernate.criterion.Criterion criterion = Restrictions.isEmpty(calculatePropertyName(property, this.alias));
        addToCriteria(criterion);
        return this;
    }

    @Override
    public Query isNotEmpty(String property) {
        addToCriteria(Restrictions.isNotEmpty(calculatePropertyName(property, this.alias)));
        return this;
    }

    @Override
    public Query isNull(String property) {
        addToCriteria(Restrictions.isNull(calculatePropertyName(property, this.alias)));
        return this;
    }

    @Override
    public Query isNotNull(String property) {
        addToCriteria(Restrictions.isNotNull(calculatePropertyName(property, this.alias)));
        return this;
    }

    @Override
    public void add(Criterion criterion) {
        if(criterion instanceof FunctionCallingCriterion) {
            HibernateTemplate hibernateSession = (HibernateTemplate) this.session.getNativeInterface();

            SessionFactory sessionFactory = hibernateSession.getSessionFactory();
            FunctionCallingCriterion fcc = (FunctionCallingCriterion) criterion;
            String property = fcc.getProperty();
            PropertyCriterion propertyCriterion = fcc.getPropertyCriterion();
            PersistentProperty pp = entity.getPropertyByName(property);

            if(pp == null) throw new InvalidDataAccessResourceUsageException("Cannot execute function defined in query ["+fcc.getFunctionName()+"] on non-existent property ["+property+"] of ["+entity.getJavaClass()+"]");

            String functionName = fcc.getFunctionName();


            SessionFactoryImplementor impl = (SessionFactoryImplementor) sessionFactory;
            Dialect dialect = impl.getDialect();
            SQLFunction sqlFunction = dialect.getFunctions().get(functionName);
            if(sqlFunction != null) {
                TypeResolver typeResolver = impl.getTypeResolver();
                BasicType basic = typeResolver.basic(pp.getType().getName());
                if(basic != null) {

                    final org.hibernate.criterion.Criterion hibernateCriterion = new HibernateCriterionAdapter(propertyCriterion, alias).toHibernateCriterion(this);
                    if(hibernateCriterion instanceof SimpleExpression) {
                        SimpleExpression expr = (SimpleExpression) hibernateCriterion;
                        Object op = ReflectionUtils.getField(opField, expr);
                        PropertyMapping mapping = (PropertyMapping) impl.getEntityPersister(entity.getJavaClass().getName());
                        String[] columns;
                        if(this.alias != null)
                            columns = mapping.toColumns(alias, property);
                        else
                            columns = mapping.toColumns(property);
                        String root = sqlFunction.render(basic, Arrays.asList(columns), impl);
                        Object value = propertyCriterion.getValue();
                        if(value != null)
                            addToCriteria(Restrictions.sqlRestriction(root + op + "?", value, typeResolver.basic(value.getClass().getName()) ));
                        else
                            addToCriteria(Restrictions.sqlRestriction(root + op + "?", value, basic ));
                    }
                    else {
                        throw new InvalidDataAccessResourceUsageException("Unsupported function ["+functionName+"] defined in query for property ["+property+"] with type ["+pp.getType()+"]");
                    }
                }
                else {
                    throw new InvalidDataAccessResourceUsageException("Unsupported function ["+functionName+"] defined in query for property ["+property+"] with type ["+pp.getType()+"]");
                }
            }
            else {
                throw new InvalidDataAccessResourceUsageException("Unsupported function defined in query ["+functionName+"]");
            }
        }
        else if(criterion instanceof PropertyCriterion) {
            PropertyCriterion pc = (PropertyCriterion) criterion;
            if(pc.getValue() instanceof QueryableCriteria) {
                DetachedCriteria hibernateDetachedCriteria = HibernateCriteriaBuilder.getHibernateDetachedCriteria((QueryableCriteria) pc.getValue());
                pc.setValue(hibernateDetachedCriteria);
            }
        }
        final org.hibernate.criterion.Criterion hibernateCriterion = new HibernateCriterionAdapter(criterion, alias).toHibernateCriterion(this);
        if (hibernateCriterion != null) {
            addToCriteria(hibernateCriterion);
        }
    }

    @Override
    public Junction disjunction() {
        final org.hibernate.criterion.Disjunction disjunction = Restrictions.disjunction();
        addToCriteria(disjunction);
        return new HibernateJunction(disjunction, this.alias);
    }

    @Override
    public Junction negation() {
        final org.hibernate.criterion.Disjunction disjunction = Restrictions.disjunction();
        addToCriteria(Restrictions.not(disjunction));
        return new HibernateJunction(disjunction, this.alias);
    }

    @Override
    public Query eq(String property, Object value) {
        addToCriteria(Restrictions.eq(calculatePropertyName(property, this.alias), value));
        return this;
    }

    @Override
    public Query idEq(Object value) {
        addToCriteria(Restrictions.idEq(value));
        return this;
    }

    @Override
    public Query gt(String property, Object value) {
        addToCriteria(Restrictions.gt(calculatePropertyName(property, this.alias), value));
        return this;
    }


    @Override
    public Query and(Criterion a, Criterion b) {
        HibernateCriterionAdapter aa = new HibernateCriterionAdapter(a, alias);
        HibernateCriterionAdapter ab = new HibernateCriterionAdapter(a, alias);
        addToCriteria(Restrictions.and(aa.toHibernateCriterion(this), ab.toHibernateCriterion(this)));
        return this;
    }

    @Override
    public Query or(Criterion a, Criterion b) {
        HibernateCriterionAdapter aa = new HibernateCriterionAdapter(a, alias);
        HibernateCriterionAdapter ab = new HibernateCriterionAdapter(a, alias);
        addToCriteria(Restrictions.or(aa.toHibernateCriterion(this), ab.toHibernateCriterion(this)));
        return this;

    }

    @Override
    public Query allEq(Map<String, Object> values) {
        addToCriteria(Restrictions.allEq(values));
        return this;
    }

    @Override
    public Query ge(String property, Object value) {
        addToCriteria(Restrictions.ge(calculatePropertyName(property, this.alias), value));
        return this;
    }

    @Override
    public Query le(String property, Object value) {
        addToCriteria(Restrictions.le(calculatePropertyName(property, this.alias), value));
        return this;
    }

    @Override
    public Query gte(String property, Object value) {
        addToCriteria(Restrictions.ge(calculatePropertyName(property, this.alias), value));
        return this;
    }

    @Override
    public Query lte(String property, Object value) {
        addToCriteria(Restrictions.le(calculatePropertyName(property, this.alias), value));
        return this;
    }

    @Override
    public Query lt(String property, Object value) {
        addToCriteria(Restrictions.lt(calculatePropertyName(property, this.alias), value));
        return this;
    }

    @Override
    public Query in(String property, List values) {
        addToCriteria(Restrictions.in(calculatePropertyName(property, this.alias), values));
        return this;
    }

    @Override
    public Query between(String property, Object start, Object end) {
        addToCriteria(Restrictions.between(calculatePropertyName(property, this.alias), start, end));
        return this;
    }

    @Override
    public Query like(String property, String expr) {
        addToCriteria(Restrictions.like(calculatePropertyName(property, this.alias), calculatePropertyName(expr, this.alias)));
        return this;
    }

    @Override
    public Query ilike(String property, String expr) {
        addToCriteria(Restrictions.ilike(calculatePropertyName(property, this.alias), calculatePropertyName(expr, this.alias)));
        return this;
    }

    @Override
    public Query rlike(String property, String expr) {
        addToCriteria(new RlikeExpression(calculatePropertyName(property, this.alias), calculatePropertyName(expr, this.alias)));
        return this;
    }

    @Override
    public AssociationQuery createQuery(String associationName) {
        final PersistentProperty property = entity.getPropertyByName(calculatePropertyName(associationName, this.alias));
        if (property != null && (property instanceof Association)) {
            String alias = generateAlias(associationName);
            CriteriaAndAlias subCriteria = getOrCreateAlias(associationName, alias);

            Association association = (Association) property;
            return new HibernateAssociationQuery(subCriteria.criteria, (HibernateSession) getSession(), association.getAssociatedEntity(), association, alias);
        }
        throw new InvalidDataAccessApiUsageException("Cannot query association [" + calculatePropertyName(associationName, this.alias) + "] of entity [" + entity + "]. Property is not an association!");
    }

    private CriteriaAndAlias getOrCreateAlias(String associationName, String alias) {
        CriteriaAndAlias subCriteria;
        if(createdAssociationPaths.containsKey(associationName)) {
            subCriteria = createdAssociationPaths.get(associationName);
        }
        else {
            Criteria sc = criteria.createAlias(associationName, alias);
            subCriteria = new CriteriaAndAlias(sc, alias);
            createdAssociationPaths.put(associationName,subCriteria );
        }
        return subCriteria;
    }

    @Override
    public ProjectionList projections() {
        hibernateProjectionList = new HibernateProjectionList();
        return hibernateProjectionList;
    }

    @Override
    public Query max(@SuppressWarnings("hiding") int max) {
        criteria.setMaxResults(max);
        return this;
    }

    @Override
    public Query maxResults(@SuppressWarnings("hiding") int max) {
        criteria.setMaxResults(max);
        return this;
    }

    @Override
    public Query offset(@SuppressWarnings("hiding") int offset) {
        criteria.setFirstResult(offset);
        return this;
    }

    @Override
    public Query firstResult(@SuppressWarnings("hiding") int offset) {
        offset(offset);
        return this;
    }

    @Override
    public Query order(Order order) {
        super.order(order);
        criteria.addOrder(order.getDirection() == Order.Direction.ASC ?
                org.hibernate.criterion.Order.asc(calculatePropertyName(order.getProperty(), this.alias)) :
                org.hibernate.criterion.Order.desc(calculatePropertyName(order.getProperty(), this.alias)));
        return this;
    }

    @Override
    public List list() {
        criteria.setResultTransformer(CriteriaSpecification.DISTINCT_ROOT_ENTITY);
        return criteria.list();
    }

    @Override
    protected void flushBeforeQuery() {
        // do nothing
    }

    @Override
    public Object singleResult() {
        if (hibernateProjectionList != null) {
            this.criteria.setProjection(hibernateProjectionList.getHibernateProjectionList());
        }
        criteria.setResultTransformer(CriteriaSpecification.DISTINCT_ROOT_ENTITY);
        return criteria.uniqueResult();
    }

    @SuppressWarnings("hiding")
    @Override
    protected List executeQuery(PersistentEntity entity, Junction criteria) {
        if (hibernateProjectionList != null) {
            this.criteria.setProjection(hibernateProjectionList.getHibernateProjectionList());
        }
        this.criteria.setResultTransformer(CriteriaSpecification.DISTINCT_ROOT_ENTITY);
        return this.criteria.list();
    }

    String handleAssociationQuery(Association<?> association, List<Criterion> criteriaList) {
        String associationName = calculatePropertyName(association.getName(), this.alias);
        String newAlias = generateAlias(associationName);
        CriteriaAndAlias criteriaAndAlias = getOrCreateAlias(associationName, newAlias);

        return criteriaAndAlias.alias;
    }

    private void addToCriteria(org.hibernate.criterion.Criterion criterion) {
        if (criterion != null) {
            criteria.add(criterion);
        }
    }

    private String calculatePropertyName(String property, String alias) {
        if (alias != null) {
            return alias + '.' + property;
        } else {
            return property;
        }
    }

    private String generateAlias(String associationName) {
        return calculatePropertyName(associationName, this.alias) + calculatePropertyName(ALIAS, this.alias) + aliasCount++;
    }


    private class HibernateJunction extends Junction {

        private org.hibernate.criterion.Junction hibernateJunction;
        private String alias;

        public HibernateJunction(org.hibernate.criterion.Junction junction) {
            this.hibernateJunction = junction;
        }

        public HibernateJunction(org.hibernate.criterion.Junction junction, String alias) {
            this.hibernateJunction = junction;
            this.alias = alias;
        }

        @Override
        public Junction add(Criterion c) {
            if (c != null) {
                HibernateCriterionAdapter adapter = new HibernateCriterionAdapter(c, this.alias);
                org.hibernate.criterion.Criterion criterion = adapter.toHibernateCriterion(HibernateQuery.this);
                if (criterion != null) {
                    hibernateJunction.add(criterion);
                }
            }
            return this;
        }
    }

    private class HibernateProjectionList extends ProjectionList {

        org.hibernate.criterion.ProjectionList projectionList = Projections.projectionList();

        public org.hibernate.criterion.ProjectionList getHibernateProjectionList() {
            return projectionList;
        }

        @Override
        public org.grails.datastore.mapping.query.api.ProjectionList countDistinct(String property) {
            projectionList.add(Projections.countDistinct(calculatePropertyName(property, HibernateQuery.this.alias)));
            return this;
        }

        @Override
        public org.grails.datastore.mapping.query.api.ProjectionList distinct(String property) {
            projectionList.add(Projections.distinct(Projections.property(calculatePropertyName(property, HibernateQuery.this.alias))));
            return this;
        }

        @Override
        public org.grails.datastore.mapping.query.api.ProjectionList rowCount() {
            projectionList.add(Projections.rowCount());
            return this;
        }

        @Override
        public ProjectionList id() {
            projectionList.add(Projections.id());
            return this;
        }

        @Override
        public ProjectionList count() {
            projectionList.add(Projections.rowCount());
            return this;
        }

        @Override
        public ProjectionList property(String name) {
            projectionList.add(Projections.property(calculatePropertyName(name, HibernateQuery.this.alias)));
            return this;
        }

        @Override
        public ProjectionList sum(String name) {
            projectionList.add(Projections.sum(calculatePropertyName(name, HibernateQuery.this.alias)));
            return this;
        }

        @Override
        public ProjectionList min(String name) {
            projectionList.add(Projections.min(calculatePropertyName(name, HibernateQuery.this.alias)));
            return this;
        }

        @Override
        public ProjectionList max(String name) {
            projectionList.add(Projections.max(calculatePropertyName(name, HibernateQuery.this.alias)));
            return this;
        }

        @Override
        public ProjectionList avg(String name) {
            projectionList.add(Projections.avg(calculatePropertyName(name, HibernateQuery.this.alias)));
            return this;
        }

        @Override
        public ProjectionList distinct() {
            criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
            return this;
        }
    }

    private class HibernateAssociationQuery extends AssociationQuery {

        private String alias;
        private org.hibernate.criterion.Junction hibernateJunction;
        private Criteria assocationCriteria;

        public HibernateAssociationQuery(Criteria criteria, HibernateSession session, PersistentEntity associatedEntity, Association association, String alias) {
            super(session, associatedEntity, association);
            this.alias = alias;
            this.assocationCriteria = criteria;
        }

        @Override
        public Query isEmpty(String property) {
            org.hibernate.criterion.Criterion criterion = Restrictions.isEmpty(calculatePropertyName(property, this.alias));
            addToCriteria(criterion);
            return this;
        }

        private void addToCriteria(org.hibernate.criterion.Criterion criterion) {
           if(hibernateJunction != null) {
               hibernateJunction.add(criterion);
           }
           else {
               assocationCriteria.add(criterion);
           }
        }

        @Override
        public Query isNotEmpty(String property) {
            addToCriteria(Restrictions.isNotEmpty(calculatePropertyName(property, this.alias)));
            return this;
        }

        @Override
        public Query isNull(String property) {
            addToCriteria(Restrictions.isNull(calculatePropertyName(property, this.alias)));
            return this;
        }

        @Override
        public Query isNotNull(String property) {
            addToCriteria(Restrictions.isNotNull(calculatePropertyName(property, this.alias)));
            return this;
        }

        @Override
        public void add(Criterion criterion) {
            final org.hibernate.criterion.Criterion hibernateCriterion = new HibernateCriterionAdapter(criterion, alias).toHibernateCriterion(HibernateQuery.this);
            if (hibernateCriterion != null) {
                addToCriteria(hibernateCriterion);
            }
        }

        @Override
        public Junction disjunction() {
            final org.hibernate.criterion.Disjunction disjunction = Restrictions.disjunction();
            addToCriteria(disjunction);
            return new HibernateJunction(disjunction, this.alias);
        }

        @Override
        public Junction negation() {
            final org.hibernate.criterion.Disjunction disjunction = Restrictions.disjunction();
            addToCriteria(Restrictions.not(disjunction));
            return new HibernateJunction(disjunction, this.alias);
        }

        @Override
        public Query eq(String property, Object value) {
            addToCriteria(Restrictions.eq(calculatePropertyName(property, this.alias), value));
            return this;
        }

        @Override
        public Query idEq(Object value) {
            addToCriteria(Restrictions.idEq(value));
            return this;
        }

        @Override
        public Query gt(String property, Object value) {
            addToCriteria(Restrictions.gt(calculatePropertyName(property, this.alias), value));
            return this;
        }


        @Override
        public Query and(Criterion a, Criterion b) {
            HibernateCriterionAdapter aa = new HibernateCriterionAdapter(a, alias);
            HibernateCriterionAdapter ab = new HibernateCriterionAdapter(a, alias);
            addToCriteria(Restrictions.and(aa.toHibernateCriterion(HibernateQuery.this), ab.toHibernateCriterion(HibernateQuery.this)));
            return this;
        }

        @Override
        public Query or(Criterion a, Criterion b) {
            HibernateCriterionAdapter aa = new HibernateCriterionAdapter(a, alias);
            HibernateCriterionAdapter ab = new HibernateCriterionAdapter(a, alias);
            addToCriteria(Restrictions.or(aa.toHibernateCriterion(HibernateQuery.this), ab.toHibernateCriterion(HibernateQuery.this)));
            return this;

        }

        @Override
        public Query allEq(Map<String, Object> values) {
            addToCriteria(Restrictions.allEq(values));
            return this;
        }

        @Override
        public Query ge(String property, Object value) {
            addToCriteria(Restrictions.ge(calculatePropertyName(property, this.alias), value));
            return this;
        }

        @Override
        public Query le(String property, Object value) {
            addToCriteria(Restrictions.le(calculatePropertyName(property, this.alias), value));
            return this;
        }

        @Override
        public Query gte(String property, Object value) {
            addToCriteria(Restrictions.ge(calculatePropertyName(property, this.alias), value));
            return this;
        }

        @Override
        public Query lte(String property, Object value) {
            addToCriteria(Restrictions.le(calculatePropertyName(property, this.alias), value));
            return this;
        }

        @Override
        public Query lt(String property, Object value) {
            addToCriteria(Restrictions.lt(calculatePropertyName(property, this.alias), value));
            return this;
        }

        @Override
        public Query in(String property, List values) {
            addToCriteria(Restrictions.in(calculatePropertyName(property, this.alias), values));
            return this;
        }

        @Override
        public Query between(String property, Object start, Object end) {
            addToCriteria(Restrictions.between(calculatePropertyName(property, this.alias), start, end));
            return this;
        }

        @Override
        public Query like(String property, String expr) {
            addToCriteria(Restrictions.like(calculatePropertyName(property, this.alias), calculatePropertyName(expr, this.alias)));
            return this;
        }

        @Override
        public Query ilike(String property, String expr) {
            addToCriteria(Restrictions.ilike(calculatePropertyName(property, this.alias), calculatePropertyName(expr, this.alias)));
            return this;
        }

        @Override
        public Query rlike(String property, String expr) {
            addToCriteria(new RlikeExpression(calculatePropertyName(property, this.alias), calculatePropertyName(expr, this.alias)));
            return this;
        }

        public void setJunction(org.hibernate.criterion.Junction hibernateJunction) {
            this.hibernateJunction = hibernateJunction;
        }
    }

    private class CriteriaAndAlias {
        private Criteria criteria;
        private String alias;

        public CriteriaAndAlias(Criteria subCriteria, String alias) {
            this.criteria = subCriteria;
            this.alias = alias;
        }
    }
}
