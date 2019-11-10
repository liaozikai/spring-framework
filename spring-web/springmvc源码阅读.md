[TOC]

# springmvc简易解析

 网络上有许多优秀学习资源，由于很多都已经解析的够通透了，本文只是作为个人学习的记录，加强记忆，理清思路。并会贴上相应的链接，以便回忆。

​	<https://www.cnblogs.com/yaohong/p/5905750.html>

​	<https://blog.csdn.net/mynewclass/article/details/78501604>

上面两个链接是对web.xml文件中标签的详细解析，其中，srping mvc + spring  + hiberante的框架中，web.xml常见会配置3个文件。一个是web.xml，该文件主要配置了servlet，listener和context-param这几个重要参数。

servlet标签主要配置前端控制器*DispatcherServlet*和配置请求地址拦截url。

listener标签配置了上下文加载监听器，当容器启动时会监听并初始化和加载bean

context-param标签主要是配置spring bean的文件文件所在目录

​	注意：tomcat，jetty等服务器启动时，会受到ServletContextListener的监听，故而web应用的启动由此开始。在org.apache.catalina.core.StandardContext 类中，listenerStart()方法就会找到所有的监听器，然后listener.contextInitialized(tldEvent) 来启动监听器。下面的链接是自定义监听器的好例子。<https://www.cnblogs.com/kingxiaozi/p/3980749.html> 当我们分析时，从org.springframework.web.context.ContextLoaderListener 开始分析即可。tomcat在启动过程，除了会调用监听器之外，还会调用初始化servlet的方法。其中，StandardContext类的startInternal中的loadOnStartup(findChildren())就是加载servlet的过程，而web.xml一般配置的是DispatcherServlet，故而会调用其构造方法进行初始化。（StandardContext 的startInternal方法有加载容器的整个过程，有整个加载的逻辑，是tomcat与spring结合的重要方法）。加载listerner的过程一般被认为是加载业务容器的过程，而加载DispatcherServlet是web容器的过程。可参考<https://www.tianxiaobo.com/2018/06/30/Spring-MVC-%E5%8E%9F%E7%90%86%E6%8E%A2%E7%A7%98-%E5%AE%B9%E5%99%A8%E7%9A%84%E5%88%9B%E5%BB%BA%E8%BF%87%E7%A8%8B/>

​	

​	

本文以Tomcat作为应用服务器，用于tomcat启动过程中加载DispatcherServlet过程的解析。其他应用服务器如Jetty，应该也是类似的加载机制，都是通过监听器来启动spring的相关服务的。下面是顺序图20多个方法的相关简洁描述，主要描述方法所在类已经该方法的主要作用，更加具体的可在代码中查看。在调用过程中本文忽略了调用到的其他方法，这些方法并不影响加载的分析。若是其他胖友看了描述有歧义的，请加我qq联系，大家一同学习进步。

1.用户点击tomcat服务的startup.bat后，tomcat会加载容器的各个组件，包括Engine，host，context等，不多描述。其中，context的标准实现类就是StandadrdContext。启动过程会调用到startInternal()方法，这个方法就是会启动相应的服务，如监听器，过滤器，加载和初始化servlet等。

2.StandardContext通过startInternal启动监听器，就调用到了listenerStart()方法，该方法会找到所有的应用监听器，并且执行这些监听器中的contextInitialized()方法。因此，从这里可以看出，只要实现了某个监听器接口，成为监听实现类，并且实现contextInitialized()方法，就可以实现我们自定义的监听器了。

3.由于ContextLoaderListener类实现ServletContextListener类，并且实现了contextInitialized()方法，故而会加载到该方法。该方法的参数为ServletContextEvent，这个ServletContextEvent中的属性就是ServletContext sc。sc在应用启动过程中可以算的上是最重要的属性。其实现类是ApplicationContext，该类可以获得资源路径，加载资源转化为ServletContext类，能对监听器和过滤器进行操作等功能。contextInitialized()方法会调用ContextLoader类的initWebApplicationContext()方法。

4.ContextLoader的initWebApplicationContext()方法主要就是初始化web应用上下文，其实就是获取WebApplicationContext的子类实例。该子类实例默认是XmlWebApplicationContext。从名字可以观察出是从xml文件加载相关资源。看类注释，可以看到默认从/WEB-INF/applicationContext.xml加载数据。获取实例后，就对该上下文进行配置和更新。所以这个方法，调用了createWebApplicationContext()和configureAndRefreshWebApplicationContext()方法分别用于获取实例和更新该实例内容。

5.createWebApplicationContext()方法就是返回XmlWebApplicationContext(下面简称xwac)类实例。里面的逻辑是通过web.xml配置的param-name参数来获取实例化对应的ApplicationContext类实例。由于一般在web.xml我们不会配置该值，故而一般就返回XmlWebApplicationContext类实例。

6.configureAndRefreshWebApplicationContext()方法用于配置和更新xwac。该方法用ServletContext来获取web.xml的相关参数进行设置。设置完之后，就进行更新。调用到了customizeContext()和wac.refresh()方法。

7.customizeContext()方法，从名称可以看出，就是自定义上下文。还是从web.xml文件获取相关配置信息。但是由于这些参数一般我们不会配置，所以基本上可以直接略过。

8.refresh()方法调用的是AbstractApplicationContext类的方法。该方法才是真正启动整个应用的重要过程。该方法就是进行环境准备，为真正使用做前期准备。比如，实现spring ioc机制，使得所有加载的类由spring容器管理。这个过程就不细讲，将在spring篇章进行介绍。refresh方法调用到了finishRefresh()方法。

9.finishRefresh()方法就是结束该上下文的更新操作，调用LifecycleProcessor的onRefresh()方法和发布事件,发布事件调用到了publishEvent()方法。

10.publishEvent()方法就是用于发布事件给所有的监听器，该方法的参数ApplicationEvent event就是待发布的事件，可能是指定的应用或者是标准的框架事件。该方法调用到了multicastEvent()方法。

11.multicastEvent()是SimpleApplicationEventMulticaster类的。就是遍历所有的监听器，调用监听事件invokeListener()方法。

12.invokeListener()方法又调用到了doInvokeListener()方法，该方法调用到了对应监听器的onApplicationEvent()方法。而由于FrameworkServlet类中定义了个私用类ContextRefreshListener实现了ApplicationListener<ContextRefreshedEvent>，并实现了onApplicationEvent()方法，该方法就是调用FrameworkServlet的同名方法onApplicationEvent()，于是，就有了后续DispatcherServlet的初始化过程。

13.FrameworkServlet的onApplicationEvent()方法就是用于调用各个传参事件的onRefresh()方法,于是就调用到了DispatcherServlet类的onRefresh()方法，从整个的加载过程来看，经常用到了各种的设计模式，比较重要的就是模版方法模式。很多方法都是父类定义，由子类来实现。

14.DispacherServlet类的onRefresh()方法就是一个模版方法的实现方法，里面直接调用了真正的逻辑方法initStrategies()，用于初始化在相应的xml文件中配置的参数。

15.initStrategies()方法里面包含了9个方法，这9个方法定义了DispatcherServlet9个方面的内容。就是解析http请求的不同属性的内容，然后进行操作。

16.第一个方法是initMultipartResolver()。该方法初始化MultipartResolver类实例。从名称可知它是一个多部分内容解析器，解析的是request中属性为contentType的内容。contentType类型有很多，可以是multipart / form-data，json等。

17.第二个方法是initLocaleResolver()。该方法初始化LocaleResolver类实例。名称可看出是时区解析器。若是在applicantContext.xml中没有配置的话，则默认初始化DispatcherServlet.properties文件中设置的类。默认是初始化org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver。该类就是用于解析reqeust中Accept-Language属性的内容。

18.第三个方法是initThemeResolver()。该方法初始化ThemeResolver类实例。名称可看出是主体解析器。若是无配置，则默认解析org.springframework.web.servlet.theme.FixedThemeResolver。该类就是设置主体名称。具体用法可参考 https://segmentfault.com/u/dalianghe 里面的内容，写的很好。在整体上进行解析，结合实例，能够很好的让人理解theme和view的用法。

19.第四个方法是initHandlerMappings()。该方法用于初始化映射控制器。该映射控制器用于解析request请求映射到那个控制器方法的。若是无配置，默认会解析org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping,\   org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping。加载了两个mapping实例，就是用于解析不同的请求方式。

 https://www.cnblogs.com/tengyunhao/p/7658952.html 这边文章解释了关于mapping和adapter的相关知识点。其中，文章中提及了很多映射方式是一个controller对应一个请求，是通过参数值来确定方法的。但现如今大部分都是一个请求是对应一个controller中的一个方法的，也就是现在大部分都是采用RequestMappingHandlerMapping的请求机制。具体的没有必要深究，没必要去研究其他请求机制，等到程序中用到的话再研究下即可，因为个人感觉一个controller对应一个请求的方式，现在都怎么遇到过。handlerMapping就是解析请求，将对应的请求解析到对应的控制器方法这么一个映射机制。

20.第五个方法是initHandlerAdapters()。该方法用于将控制器方法业务逻辑处理后得到的数据进行封装操作，进行转换得到相应的视图模型，也就是ModelAndView对象。策略初始化默认的是org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter,\   org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter,\   org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter这三个类实例。转化过程中，进行了相应的操作，如数据绑定，参数绑定，执行方法，创建模型和视图容器的操作，最终得到该对象。

21.第六个方法是initHandlerExceptionResolvers()。该方法用于初始化异常控制解析器。若是没有在xml文件中设置，则默认没有。

22.第7个方法是initRequestToViewNameTranslator()。默认初始化org.springframework.web.servlet.view.DefaultRequestToViewNameTranslator类实例。从名称得出就是将url的请求直接映射请求到文件的操作。如，http://localhost:8080/gamecast/display.html可以直接访问到display.html文件。

23.第8个方法是initViewResolvers()，该方法默认初始化org.springframework.web.servlet.view.InternalResourceViewResolver类实例。该视图解析器主要是将ModelAndView的内容解析成对应的视图，该视图是用来渲染页面的，也就是说可以将model中的内容填充到模版中，生成html或其他格式的文件。视图解析器可以设置多个解析策略，如可以用jsp解析，也就是解析jstl标签内容；可以解析Velocity。（个人感觉就是在html的基础上，对于各种标签的解析，如c标签，jstl，struts，还有spring自定义的标签等）

24.第9个方法就是initFlashMapManager()。该方法默认初始化org.springframework.web.servlet.support.SessionFlashMapManager类实例。类注释描述的是用于保存到HttpSession和从HttpSession中搜索到FlashMap实例。就是提供一个保存和查询的机制，类似session。没怎么对其描述，就不多说了。

​	总结：通过顺序图和对24个方法的描述，基本上从应用服务启动再到DispatcherServlet资源和策略加载的整个过程都有个大概的了解。而更加深入的了解则只能通过代码来入手。springmvc的整体理解并不难，但是，从代码入手真的是相当棘手。只能硬着头皮上，能理解多少是多少。在实战过程中遇到问题再进行调试来加深理解是更为合理恰当的方式。



































