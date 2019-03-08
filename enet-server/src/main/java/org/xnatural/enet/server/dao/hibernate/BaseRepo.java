package org.xnatural.enet.server.dao.hibernate;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.xnatural.enet.common.Log;

import javax.annotation.PostConstruct;
import javax.persistence.NoResultException;
import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.Objects;

public class BaseRepo<T extends IEntity, ID extends Serializable> {
    protected Log             log = Log.of(getClass());
    protected SessionFactory  sf;
    protected HibernateServer hs;
    protected Class<T>        entityType;
    protected Class<ID>       idType;
    /**
     * 分页时, 每页最大条数
     */
    protected int             maxPageSize;
    /**
     * 默认pageSize
     */
    protected int             defaultPageSize;


    @PostConstruct
    protected void init() {
        Type genType = getClass().getGenericSuperclass();
        if (genType instanceof ParameterizedType) {
            Type[] params = ((ParameterizedType) genType).getActualTypeArguments();
            if (params[0] instanceof Class) {
                entityType = (Class) params[0];
                idType = (Class) params[1];
            }
        }
        Objects.requireNonNull(entityType, "domainType is null");
        maxPageSize = hs.getInteger("max-page-size." + entityType.getSimpleName(), hs.getInteger("default-max-page-size", 30));
        defaultPageSize = hs.getInteger("page-size." + entityType.getSimpleName(), hs.getInteger("default-page-size", 10));
    }


    /**
     * {@link #entityType} 实体对应的表名
     * @return
     */
    public String tbName() {
        return tbName((Class<IEntity>) entityType);
    }


    public String tbName(Class<IEntity> eClass) {
        // return ((AbstractEntityPersister) ((SessionImpl) em.getDelegate()).getFactory().getMetamodel().locateEntityPersister(entityClass)).getRootTableName();
        return null;
    }


    public <S extends T> S saveOrUpdate(S e) {
        if (e instanceof BaseEntity) {
            Date d = new Date();
            if (((BaseEntity) e).getCreateTime() == null) ((BaseEntity) e).setCreateTime(d);
            ((BaseEntity) e).setUpdateTime(d);
        }
        sf.getCurrentSession().saveOrUpdate(e);
        return e;
    }


    public T findById(ID id) {
        return sf.getCurrentSession().get(entityType, id);
    }


    public T findOne(Query query) {
        try {
            return (T) query.setMaxResults(1).getSingleResult();
        } catch (NoResultException e) {}
        return null;
    }


    public boolean delete(ID id) {
        // NOTE: 被删除的实体主键名必须为 "id";
        return sf.getCurrentSession().createQuery("delete from " + entityType.getSimpleName() + " where id=:id")
            .setParameter("id", id)
            .executeUpdate() > 0;
    }


    public void delete(T e) {
        sf.getCurrentSession().delete(e);
    }


    public Page<T> findPage(Integer pageIndex, Integer pageSize, Specification spec) {
        Session s = sf.getCurrentSession();
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityType);
        Root<T> root = query.from(entityType);
        Predicate p = (spec == null ? null : spec.toPredicate(root, query, cb));
        if (p != null) query.where(p);
        int ps = (pageSize == null ? defaultPageSize : (pageSize > maxPageSize ? defaultPageSize : pageSize));
        int pi = (pageIndex == null ? 0 : pageIndex);
        return new Page<T>(
            s.createQuery(query).setFirstResult(pi * ps).setMaxResults(ps).list(),
            pi, ps, (int) (Math.ceil(count(spec) / ps))
        );
    }


    public long count(Specification spec) {
        Session s = sf.getCurrentSession();
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<T> root = query.from(entityType);
        Predicate p = spec == null ? null : spec.toPredicate(root, query, cb);
        if (query.isDistinct()) query.select(cb.countDistinct(root));
        else query.select(cb.count(root));
        if (p != null) query.where(p);
        return s.createQuery(query).getSingleResult();
    }
}