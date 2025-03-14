package org.jolokia.support.jmx;

/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

import org.jolokia.service.serializer.JolokiaSerializer;
import org.jolokia.json.JSONArray;
import org.jolokia.json.JSONObject;
import org.jolokia.json.parser.JSONParser;
import org.jolokia.json.parser.ParseException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * @author roland
 * @since 26.01.13
 */
public class JsonDymamicMBeanImplTest {

    private MBeanServer server;
    private MBeanServer        platformServer;
    private ObjectName         testName;
    private ObjectName         userManagerName;

    @BeforeClass
    public void setup() {
        server = (MBeanServer) Proxy.newProxyInstance(JolokiaMBeanServerHolder.class.getClassLoader(), new Class[]{MBeanServer.class},
                                                      new JolokiaMBeanServerHandler(new JolokiaSerializer()));
        platformServer = ManagementFactory.getPlatformMBeanServer();
    }

    @BeforeMethod
    public void registerBeans() throws Exception {
        testName = on("test:type=json");
        userManagerName = on("test:type=mxbean");
        register(testName, new Testing());
        register(userManagerName, new UserTestManager());
    }

    @AfterMethod
    public void unregisterBeans() throws Exception {
        unregister(testName);
        unregister(userManagerName);
    }

    @Test
    public void getAttribute() throws Exception {
        assertEquals(platformServer.getAttribute(testName, "Chili"), "jolokia");
        String user = (String) platformServer.getAttribute(testName,"User");
        JSONObject userJ = toJSON(user, JSONObject.class);
        assertEquals(userJ.get("firstName"), "Hans");
        assertEquals(userJ.get("lastName"), "Kalb");
    }

    @Test
    public void checkMBeanInfo() throws Exception {
        MBeanInfo info = platformServer.getMBeanInfo(testName);
        MBeanAttributeInfo[] aInfo = info.getAttributes();
        Map<String,MBeanAttributeInfo> attributes = new HashMap<>();
        for (MBeanAttributeInfo a : aInfo) {
            attributes.put(a.getName(),a);
        }

        assertEquals(attributes.get("Chili").getType(), String.class.getName());
        assertEquals(attributes.get("Numbers").getType(), String.class.getName());
        assertEquals(attributes.get("User").getType(), String.class.getName());

        MBeanOperationInfo[] oInfo = info.getOperations();
        Map<String,MBeanOperationInfo> ops = new HashMap<>();
        for (MBeanOperationInfo o : oInfo) {
            ops.put(o.getName(), o);
        }

        assertEquals(ops.get("lookup").getReturnType(), String.class.getName());
        assertEquals(ops.get("epoch").getReturnType(), "long");
        assertEquals(ops.get("charTest").getReturnType(), "char");

        MBeanParameterInfo[] p = ops.get("lookup").getSignature();
        assertEquals(p[0].getType(), String.class.getName());
        assertEquals(p[1].getType(), String.class.getName());

        p = ops.get("epoch").getSignature();
        assertEquals(p[0].getType(), String.class.getName());

        p = ops.get("charTest").getSignature();
        assertEquals(p[0].getType(), Character.class.getName());

    }

    private <T> T toJSON(String string, Class<T> clazz) throws ParseException, IOException {
        return new JSONParser().parse(string, clazz);
    }

    @Test
    public void setAttribute() throws Exception {
        platformServer.setAttribute(testName,new Attribute("Chili","fatalii"));
        assertEquals(platformServer.getAttribute(testName, "Chili"), "fatalii");

        platformServer.setAttribute(testName,new Attribute("Numbers","8,15"));
        String nums = (String) platformServer.getAttribute(testName,"Numbers");
        JSONArray numsJ = toJSON(nums, JSONArray.class);
        assertEquals(numsJ.get(0), 8L);
        assertEquals(numsJ.get(1), 15L);
        assertEquals(numsJ.size(), 2);
    }

    @Test
    public void exec() throws Exception {
        String res = (String) platformServer.invoke(testName,"lookup",new Object[] { "Bumbes", "Eins, Zwei" }, new String[] { "java.lang.String", "java.lang.String" });
        JSONObject user = toJSON(res, JSONObject.class);
        assertEquals(user.get("firstName"), "Hans");
        assertEquals(user.get("lastName"), "Kalb");

        Date date = new Date();
        long millis = (Long) platformServer.invoke(testName,"epoch", new Object[] { date.getTime() + "" }, new String[] { "java.lang.String"});
        assertEquals(date.getTime(), millis);

        char c = (Character) platformServer.invoke(testName,"charTest", new Object[] { 'y' }, new String[] { Character.class.getName() });
        assertEquals(c, 'y');
    }

    @Test
    public void setGetAttributes() throws Exception {
        AttributeList attrList = new AttributeList(Arrays.asList(
                new Attribute("Chili","aji"),
                new Attribute("Numbers","16,11,68")));
        platformServer.setAttributes(testName,attrList);

        AttributeList ret =  platformServer.getAttributes(testName,new String[] { "Chili", "Numbers" });
        Attribute chili = (Attribute) ret.get(0);
        Attribute num = (Attribute) ret.get(1);
        assertEquals(chili.getValue(), "aji");

        JSONArray numsJ = toJSON((String) num.getValue(), JSONArray.class);
        assertEquals(numsJ.get(0), 16L);
        assertEquals(numsJ.get(1), 11L);
        assertEquals(numsJ.get(2), 68L);
        assertEquals(numsJ.size(), 3);

        assertEquals(platformServer.getAttributes(testName, new String[0]).size(), 0);

    }


    @Test(expectedExceptions = RuntimeMBeanException.class, expectedExceptionsMessageRegExp = ".*convert.*")
    public void unconvertableArgument() throws AttributeNotFoundException, MBeanException, ReflectionException, InstanceNotFoundException, InvalidAttributeValueException {
        User other = new User("Max","Morlock");
        platformServer.setAttribute(testName, new Attribute("User", other.toJSONString()));
    }

    @Test
    public void openMBean() throws AttributeNotFoundException, MBeanException, ReflectionException, InstanceNotFoundException, InvalidAttributeValueException, ParseException, IOException {
        platformServer.setAttribute(userManagerName,new Attribute("User","{\"firstName\": \"Bumbes\", \"lastName\": \"Schmidt\"}"));
        String user = (String) platformServer.getAttribute(userManagerName,"User");
        JSONObject userJ = toJSON(user, JSONObject.class);
        assertEquals(userJ.get("firstName"), "Bumbes");
        assertEquals(userJ.get("lastName"), "Schmidt");

        user = (String) platformServer.invoke(userManagerName,"lookup",
                              new Object[] {
                                      "Schmidt",
                                      "[{\"firstName\": \"Mama\", \"lastName\": \"Schmidt\"}," +
                                       "{\"firstName\": \"Papa\", \"lastName\": \"Schmidt\"}]"},
                              new String[] { String.class.getName(), String.class.getName() });
        userJ = toJSON(user, JSONObject.class);
        assertEquals(userJ.get("firstName"), "Bumbes");
        assertEquals(userJ.get("lastName"), "Schmidt");
    }


    @AfterClass
    public void teardown() {
    }

    private ObjectName on(String name) throws MalformedObjectNameException {
        return new ObjectName(name);

    }

    private JsonDynamicMBeanImpl register(ObjectName oName, Object bean) throws Exception {
        server.registerMBean(bean, oName);

        JsonDynamicMBeanImpl jsonMBean = new JsonDynamicMBeanImpl(server,oName,server.getMBeanInfo(oName),
                                                                  new JolokiaSerializer(),null);
        platformServer.registerMBean(jsonMBean,oName);

        return jsonMBean;
    }

    private void unregister(ObjectName oName) throws Exception {
        server.unregisterMBean(oName);
        platformServer.unregisterMBean(oName);
    }


    // ===============================================================================
    // Test MBean

    public interface UserTestManagerMXBean {
        void setUser(User user);

        User getUser();

        User lookup(String name, User[] parents);
    }

    public static class UserTestManager implements UserTestManagerMXBean {
        User user = new User("Hans", "Kalb");

        public void setUser(User user) {
            this.user = user;
        }

        public User getUser() {
            return user;
        }

        public User lookup(String name, User[] parents) {
            return user;
        }
    }

    public interface TestingMBean {
        String getChili();

        void setChili(String name);

        int[] getNumbers();
        void setNumbers(int[] numbers);
        User getUser();

        User lookup(String name, String[] parents);
        long epoch(Date date);

        char charTest(Character c);
    }

    public static class User {
        private String firstName, lastName;

        public User() {
        }

        public User(String pFirstName, String pLastName) {
            firstName = pFirstName;
            lastName = pLastName;
        }

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setFirstName(String pFirstName) {
            firstName = pFirstName;
        }

        public void setLastName(String pLastName) {
            lastName = pLastName;
        }

        public String toJSONString() {
            return "{\"firstName\": \"" + firstName + "\", \"lastName\" : \"" + lastName + "\"}";
        }
    }

    public static class Testing implements TestingMBean {
        String chili = "jolokia";
        int[] numbers = new int[] { 47, 11};
        User user = new User("Hans", "Kalb");
        public String getChili() {
            return chili;
        }

        public void setChili(String name) {
            chili = name;
        }

        public int[] getNumbers() {
            return numbers;
        }

        public void setNumbers(int[] numbers) {
            this.numbers = numbers;
        }

        public User lookup(String name, String[] parents) {
            return user;
        }

        public long epoch(Date date) {
            return date.getTime();
        }

        public char charTest(Character c) {
            return c;
        }

        public User getUser() {
            return user;
        }
    }
}
