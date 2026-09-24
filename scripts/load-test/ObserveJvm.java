import com.sun.tools.attach.VirtualMachine;
import javax.management.*;
import javax.management.remote.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** 本机短批次压测采样器；通过本地 Attach 读取应用 JMX，不修改业务代码。 */
public class ObserveJvm {
    static Object attr(MBeanServerConnection c, ObjectName n, String key) {
        try { return c.getAttribute(n, key); } catch (Exception e) { return null; }
    }
    static long number(Object x) { return x instanceof Number ? ((Number)x).longValue() : -1; }
    static double decimal(Object x) { return x instanceof Number ? ((Number)x).doubleValue() : -1; }
    static String safe(Object x) { return x == null ? "NA" : x.toString(); }
    public static void main(String[] args) throws Exception {
        String pid = args[0];
        long interval = Long.parseLong(args[1]);
        long duration = Long.parseLong(args[2]);
        VirtualMachine vm = VirtualMachine.attach(pid);
        String address;
        try { address = vm.startLocalManagementAgent(); } finally { vm.detach(); }
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            MBeanServerConnection c = connector.getMBeanServerConnection();
            ObjectName os = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            ObjectName mem = new ObjectName(ManagementFactory.MEMORY_MXBEAN_NAME);
            ObjectName threads = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
            Set<ObjectName> pools = new HashSet<>();
            Set<ObjectName> hikari = new HashSet<>();
            for (ObjectName name : c.queryNames(null, null)) {
                if (name.toString().contains("type=ThreadPool") && (name.getDomain().equals("Tomcat") || name.getDomain().equals("Catalina"))) pools.add(name);
                if (name.getDomain().equals("com.zaxxer.hikari") && name.getKeyProperty("type").startsWith("Pool (")) hikari.add(name);
            }
            Set<ObjectName> gcs = c.queryNames(new ObjectName("java.lang:type=GarbageCollector,*"), null);
            System.err.println("Tomcat pools=" + pools + " Hikari pools=" + hikari + " GC=" + gcs);
            System.out.println("time_ms,tomcat_busy,tomcat_threads,tomcat_max,tomcat_connections,jvm_threads,jvm_cpu_load,jvm_process_cpu_ns,heap_used_bytes,heap_committed_bytes,gc_count,gc_time_ms,hikari_active,hikari_idle,hikari_total,hikari_waiting");
            long end = System.currentTimeMillis() + duration;
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                ObjectName pool = pools.stream().findFirst().orElse(null);
                ObjectName hp = hikari.stream().findFirst().orElse(null);
                javax.management.openmbean.CompositeData heap = (javax.management.openmbean.CompositeData) attr(c, mem, "HeapMemoryUsage");
                long gcCount = 0, gcTime = 0;
                for (ObjectName gc : gcs) { gcCount += Math.max(0, number(attr(c,gc,"CollectionCount"))); gcTime += Math.max(0, number(attr(c,gc,"CollectionTime"))); }
                String[] row = {""+now, safe(pool == null ? null : attr(c,pool,"currentThreadsBusy")), safe(pool == null ? null : attr(c,pool,"currentThreadCount")), safe(pool == null ? null : attr(c,pool,"maxThreads")), safe(pool == null ? null : attr(c,pool,"connectionCount")), safe(attr(c,threads,"ThreadCount")), safe(attr(c,os,"ProcessCpuLoad")), safe(attr(c,os,"ProcessCpuTime")), safe(heap == null ? null : heap.get("used")), safe(heap == null ? null : heap.get("committed")), ""+gcCount, ""+gcTime, safe(hp == null ? null : attr(c,hp,"ActiveConnections")), safe(hp == null ? null : attr(c,hp,"IdleConnections")), safe(hp == null ? null : attr(c,hp,"TotalConnections")), safe(hp == null ? null : attr(c,hp,"ThreadsAwaitingConnection"))};
                System.out.println(String.join(",", row));
                System.out.flush();
                Thread.sleep(Math.max(1, interval - (System.currentTimeMillis() - now)));
            }
        }
    }
}
