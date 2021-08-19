package org.sitenv.vocabularies.configuration;

import org.sitenv.vocabularies.constants.VocabularyConstants;
import org.sitenv.vocabularies.loader.VocabularyLoadRunner;
import org.sitenv.vocabularies.loader.VocabularyLoaderFactory;
import org.sitenv.vocabularies.validation.NodeValidatorFactory;
import org.sitenv.vocabularies.validation.dto.GlobalCodeValidatorResults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ServiceLocatorFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.hibernate4.HibernateExceptionTranslator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.transaction.PlatformTransactionManager;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Created by Brian on 2/5/2016.
 */
@Configuration
@ComponentScan("org.sitenv.vocabularies")
@EnableJpaRepositories("org.sitenv.vocabularies.validation.repositories")
public class CodeValidatorApiConfiguration {

    @Bean
    public EntityManagerFactory entityManagerFactory() {
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(false);
        vendorAdapter.setShowSql(true);
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setJpaVendorAdapter(vendorAdapter);
        factory.setPackagesToScan("org.sitenv.vocabularies.validation.entities");
        Properties jpaProperties = new Properties();
        jpaProperties.put("hibernate.hbm2ddl.auto", "none");
        jpaProperties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
        jpaProperties.put("hibernate.format_sql", "true");
        jpaProperties.put("hibernate.show_sql", "false");
        factory.setDataSource(dataSource());
        factory.setJpaProperties(jpaProperties);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    public PlatformTransactionManager transactionManager() {
        JpaTransactionManager txManager = new JpaTransactionManager();
        txManager.setEntityManagerFactory(entityManagerFactory());
        return txManager;
    }

    @Bean
    public HibernateExceptionTranslator hibernateExceptionTranslator() {
        return new HibernateExceptionTranslator();
    }

    @Bean
    public DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.HSQL)
                .addScript("classpath:schema.sql")
                .build();
    }

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertyPlaceholderConfigurer() {
        PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer = new PropertySourcesPlaceholderConfigurer();
        propertySourcesPlaceholderConfigurer.setLocalOverride(true);
        return propertySourcesPlaceholderConfigurer;
    }

    @Bean
    public ServiceLocatorFactoryBean vocabularyLoaderFactoryServiceLocatorFactoryBean() {
        ServiceLocatorFactoryBean bean = new ServiceLocatorFactoryBean();
        bean.setServiceLocatorInterface(VocabularyLoaderFactory.class);
        return bean;
    }

    @Bean
    public VocabularyLoaderFactory vocabularyLoaderFactory() {
        return (VocabularyLoaderFactory) vocabularyLoaderFactoryServiceLocatorFactoryBean().getObject();
    }

    @Bean
    public ServiceLocatorFactoryBean vocabularyValidatorFactoryServiceLocatorFactoryBean() {
        ServiceLocatorFactoryBean bean = new ServiceLocatorFactoryBean();
        bean.setServiceLocatorInterface(NodeValidatorFactory.class);
        return bean;
    }

    @Bean
    public NodeValidatorFactory vocabularyValidatorFactory() {
        return (NodeValidatorFactory) vocabularyValidatorFactoryServiceLocatorFactoryBean().getObject();
    }

    @Autowired
    @Bean
    VocabularyLoadRunner vocabularyLoadRunner(final Environment environment, final VocabularyLoaderFactory vocabularyLoaderFactory, final DataSource dataSource){
        VocabularyLoadRunner vocabularyLoadRunner = null;
        String localCodeRepositoryDir = environment.getProperty("vocabulary.localCodeRepositoryDir");
        String localValueSetRepositoryDir = environment.getProperty("vocabulary.localValueSetRepositoryDir");
        vocabularyLoadRunner = new VocabularyLoadRunner();
        System.out.println("LOADING VOCABULARY DATABASES FROM THE FOLLOWING RESOURCES: VALUESETS - " + localValueSetRepositoryDir + " CODES - " + localCodeRepositoryDir);
        vocabularyLoadRunner.setCodeDirectory(localCodeRepositoryDir);
        vocabularyLoadRunner.setValueSetDirectory(localValueSetRepositoryDir);
        vocabularyLoadRunner.setDataSource(dataSource);
        vocabularyLoadRunner.setVocabularyLoaderFactory(vocabularyLoaderFactory);
        return vocabularyLoadRunner;
    }

    @Bean
    public static Map<VocabularyConstants.SeverityLevel, List<ConfiguredExpression>> vocabularyValidationConfigurations(ValidationConfigurationLoader configurationLoader){
        // This improves performance since it is run before the expressions are processed
        List<ConfiguredExpression> configuredExpressionList = configurationLoader.getConfigurations().getExpressions();

        Map<VocabularyConstants.SeverityLevel, List<ConfiguredExpression>> expressionsMap = new HashMap<>();
        expressionsMap.put(VocabularyConstants.SeverityLevel.INFO, configuredExpressionList);
        expressionsMap.put(VocabularyConstants.SeverityLevel.WARNING, new ArrayList<>());
        expressionsMap.put(VocabularyConstants.SeverityLevel.ERROR, new ArrayList<>());

        List<ConfiguredExpression> warningLevelConfiguredExpressions = expressionsMap.get(VocabularyConstants.SeverityLevel.WARNING);
        List<ConfiguredExpression> errorLevelConfiguredExpressions = expressionsMap.get(VocabularyConstants.SeverityLevel.ERROR);

        for (ConfiguredExpression configuredExpression : configuredExpressionList) {
            // NodeCodeSystemMatchesConfiguredCodeSystemValidator defaults to ERROR severity dynamically
            configuredExpression.getConfiguredValidators().parallelStream()
                    .filter(configuredValidator -> !configuredValidator.getName().equalsIgnoreCase("NodeCodeSystemMatchesConfiguredCodeSystemValidator"))
                    .map(configuredValidator -> configuredValidator.getConfiguredValidationResultSeverityLevel().getSeverityLevelConversion())
                    .forEach(configuredSeverityLevelConversion -> {
                        // skip may/info configurations so we only process warnings and errors
                        if (configuredSeverityLevelConversion != VocabularyConstants.SeverityLevel.INFO) {
                            warningLevelConfiguredExpressions.add(configuredExpression);
                        }
                        // skip may/info and should/warnings configurations so we only process errors
                        if (configuredSeverityLevelConversion == VocabularyConstants.SeverityLevel.ERROR) {
                            errorLevelConfiguredExpressions.add(configuredExpression);
                        }
                    });
        }
        expressionsMap.forEach((k, v) -> {
            Iterator<ConfiguredExpression> expressionIter = v.iterator();
            while (expressionIter.hasNext()) {
                ConfiguredExpression configuredExpression = expressionIter.next();
                if (configuredExpression.getConfiguredValidators().isEmpty()) {
                    expressionIter.remove();
                }
            }
        });
        return expressionsMap;
    }

    @Bean
    public DocumentBuilder documentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory domFactory =  DocumentBuilderFactory.newInstance();
        domFactory.setNamespaceAware(true);
        return domFactory.newDocumentBuilder();
    }

    @Bean
    public XPathFactory xPathFactory(){
        return XPathFactory.newInstance();
    }

    @Autowired
    @Bean
    public ValidationConfigurationLoader validationConfigurationLoader(final Environment environment){
        ValidationConfigurationLoader validationConfigurationLoader = new ValidationConfigurationLoader();
        validationConfigurationLoader.setValidationConfigurationFilePath(environment.getProperty("referenceccda.configFile"));
        validationConfigurationLoader.setUnmarshaller(castorMarshaller());
        return validationConfigurationLoader;
    }

    @Bean
    public static Jaxb2Marshaller castorMarshaller() {
        Jaxb2Marshaller jaxb2Marshaller = new Jaxb2Marshaller();
        jaxb2Marshaller.setPackagesToScan("org.sitenv.vocabularies.configuration");
        Map<String,Object> map = new HashMap<>();
        map.put("jaxb.formatted.output", true);
        jaxb2Marshaller.setMarshallerProperties(map);
        return jaxb2Marshaller;
    }
    
    @Bean
    public static GlobalCodeValidatorResults globalCodeValidatorResults() {    	
    	return new GlobalCodeValidatorResults();
    }
}
