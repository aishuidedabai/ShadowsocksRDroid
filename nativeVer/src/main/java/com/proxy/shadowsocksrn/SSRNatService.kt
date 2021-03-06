package com.proxy.shadowsocksrn

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.RemoteException
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.SparseArray
import com.proxy.shadowsocksrn.items.ConnectProfile
import com.proxy.shadowsocksrn.util.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

class SSRNatService : Service()
{
    val CMD_IPTABLES_RETURN = " -t nat -A OUTPUT -p tcp -d 0.0.0.0 -j RETURN"
    val CMD_IPTABLES_DNAT_ADD_SOCKS = " -t nat -A OUTPUT -p tcp -j DNAT --to-destination 127.0.0.1:8123"

    private var callback: ISSRServiceCallback? = null
    private val binder = SSRService()

    private var localProcess: Process? = null
    private var tunnelProcess: Process? = null
    private var pdnsdProcess: Process? = null
    private var redsocksProcess: Process? = null

    private var connProfile: ConnectProfile? = null
    @Volatile private var isNATConnected = false

    val myUid = android.os.Process.myUid()

    private val dnsAddressCache = SparseArray<String>()

    override fun onBind(intent: Intent?): IBinder? = binder

    internal inner class SSRService : ISSRService.Stub()
    {
        @Throws(RemoteException::class)
        override fun status(): Boolean = isNATConnected

        @Throws(RemoteException::class)
        override fun registerISSRServiceCallBack(cb: ISSRServiceCallback)
        {
            callback = cb
        }

        @Throws(RemoteException::class)
        override fun unRegisterISSRServiceCallBack()
        {
            callback = null
        }

        @Throws(RemoteException::class)
        override fun start(cp: ConnectProfile)
        {
            connProfile = cp
            if (checkDaemonFile())
            {
                startRunner()
            }
            else
            {
                if (callback != null)
                {
                    try
                    {
                        callback!!.onStatusChanged(Consts.STATUS_FAILED)
                    }
                    catch (ignored: Exception)
                    {
                    }
                }
            }
        }

        @Throws(RemoteException::class)
        override fun stop()
        {
            stopRunner()
            if (callback != null)
            {
                try
                {
                    callback!!.onStatusChanged(Consts.STATUS_DISCONNECTED)
                }
                catch (ignored: RemoteException)
                {
                }
            }
        }
    }

    private fun checkDaemonFile(): Boolean
    {
        for (fn in arrayOf("pdnsd", "redsocks"))
        {
            val f = File("${Consts.baseDir}$fn")
            if (f.exists())
            {
                if (!f.canRead() || !f.canExecute())
                {
                    ShellUtil().runCmd("chmod 755 ${f.absolutePath}")
                }
            }
            else
            {
                if (!copyDaemonBin(fn, f))
                {
                    return false
                }
                ShellUtil().runCmd("chmod 755 ${f.absolutePath}")
            }
        }
        return true
    }

    private fun copyDaemonBin(file: String, out: File): Boolean
    {
        val abi: String = Jni.getABI()
        val buf: ByteArray = ByteArray(
                1024 * 32)//most tf card have 16k or 32k logic unit size, may be 32k buffer is better
        try
        {
            if (out.createNewFile())
            {
                val fis = assets.open(abi + File.separator + file)
                val fos = FileOutputStream(out)
                var length: Int = fis.read(buf)
                while (length > 0)
                {
                    fos.write(buf, 0, length)
                    length = fis.read(buf)
                }
                fos.flush()
                fos.close()
                fis.close()
                return true
            }
        }
        catch (ignored: IOException)
        {
        }
        return false
    }

    private fun flushDns()
    {
        if (CommonUtils.isLollipopOrAbove())
        {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = manager.allNetworks
            val cmdBuf: MutableList<String> = arrayListOf()
            networks.forEach({ network ->
                val networkInfo = manager.getNetworkInfo(network)
                if (networkInfo.isAvailable)
                {
                    val netId = network.javaClass.getDeclaredField("netId").get(network) as Int
                    cmdBuf.add("ndc resolver flushnet $netId")
                }
            })
            ShellUtil().runRootCmd(cmdBuf.toTypedArray())
        }
        else
        {
            ShellUtil().runRootCmd(
                    arrayOf("ndc resolver flushdefaultif", "ndc resolver flushif wlan0"))
        }
    }

    private fun startSSRLocal()
    {
        val localConf = ConfFileUtil().SSRLocal.format(
                connProfile!!.server,
                connProfile!!.remotePort,
                connProfile!!.localPort,
                connProfile!!.passwd,
                connProfile!!.cryptMethod, 600)
        //
        ConfFileUtil().writeToFile(localConf, File("${Consts.baseDir}ssr-local-nat.conf"))
        //
        val sb = StringBuilder()
        sb.append(
                "${Consts.baseDir}ss-local -V -u -b 127.0.0.1 -t 600 -c ${Consts.baseDir}ssr-local-nat.conf -f ${Consts.baseDir}ssr-local-nat.pid")
        //
        var aclArray: Array<String> = arrayOf()
        if (connProfile!!.route != "all")
        {
            when (connProfile!!.route)
            {
                "bypass-lan"          -> aclArray = resources.getStringArray(R.array.private_route)
                "bypass-lan-and-list" -> aclArray = resources.getStringArray(R.array.chn_route_full)
            }
            val s = StringBuilder()
            aclArray.forEach {
                s.append(it).append(Consts.lineSept)
            }
            ConfFileUtil().writeToFile(s.toString(), File("${Consts.baseDir}acl.list"))
        }

        localProcess = ProcessBuilder()
                .command(sb.toString().split(" "))
                .redirectErrorStream(true)
                .start()
    }

    private fun startTunnel()
    {
        if (connProfile!!.dnsForward)
        {
            val tunnelConf = ConfFileUtil().SSRLocal.format(
                    connProfile!!.server,
                    connProfile!!.remotePort, 8153,
                    connProfile!!.passwd,
                    connProfile!!.cryptMethod, 10)
            ConfFileUtil().writeToFile(tunnelConf, File("${Consts.baseDir}ssr-tunnel-nat.conf"))

            val cmd = "${Consts.baseDir}ss-tunnel -u -t 10 -b 127.0.0.1 -L 8.8.8.8:53 -l 8153 -c ${Consts.baseDir}ssr-tunnel-nat.conf"

            tunnelProcess = ProcessBuilder()
                    .command(cmd.split(" "))
                    .redirectErrorStream(true)
                    .start()
        }
        else
        {
            val tunnelConf = ConfFileUtil().SSRLocal.format(
                    connProfile!!.server,
                    connProfile!!.remotePort, 8163,
                    connProfile!!.passwd,
                    connProfile!!.cryptMethod, 10)
            ConfFileUtil().writeToFile(tunnelConf, File("${Consts.baseDir}ssr-tunnel-nat.conf"))

            val cmd = "${Consts.baseDir}ss-tunnel -u -t 10 -b 127.0.0.1 -L 8.8.8.8:53 -l 8153 -c ${Consts.baseDir}ssr-tunnel-nat.conf"

            tunnelProcess = ProcessBuilder()
                    .command(cmd.split(" "))
                    .redirectErrorStream(true)
                    .start()
        }

    }

    private fun startDnsDaemon()
    {
        val pdnsd: String
        if (connProfile!!.route == "bypass-lan-and-list")
        {
            val reject = resources.getString(R.string.reject)
            val blklst = resources.getString(R.string.black_list)

            pdnsd = ConfFileUtil().PdNSdDirect.format("0.0.0.0", 8153, reject, blklst, 8163, "")
        }
        else
        {
            pdnsd = ConfFileUtil().PdNSdLocal.format("0.0.0.0", 8153, 8163, "")
        }

        ConfFileUtil().writeToFile(pdnsd, File(Consts.baseDir + "pdnsd-nat.conf"))

        val cmd = Consts.baseDir + "pdnsd -c " + Consts.baseDir + "pdnsd-nat.conf"

        pdnsdProcess = ProcessBuilder().command(cmd.split(" ")).redirectErrorStream(
                true).start()
    }

    private fun startRedsocksDaemon()
    {
        val conf = ConfFileUtil().RedSocks.format(connProfile!!.localPort)
        val cmd = "${Consts.baseDir}redsocks -c ${Consts.baseDir}redsocks-nat.conf"
        ConfFileUtil().writeToFile(conf, File("${Consts.baseDir}redsocks-nat.conf"))
        redsocksProcess = ProcessBuilder()
                .command(cmd.split(" "))
                .redirectErrorStream(true).start()
    }

    private fun killProcesses()
    {
        if (localProcess != null)
        {
            localProcess!!.destroy()
            localProcess = null
        }
        if (tunnelProcess != null)
        {
            tunnelProcess!!.destroy()
            tunnelProcess = null
        }
        if (pdnsdProcess != null)
        {
            pdnsdProcess!!.destroy()
            pdnsdProcess = null
        }
        if (redsocksProcess != null)
        {
            redsocksProcess!!.destroy()
            redsocksProcess = null
        }
        ShellUtil().runRootCmd("${CommonUtils.iptables} -t nat -F OUTPUT")
    }

    private fun setupIptables()
    {
        val init_sb: MutableList<String> = arrayListOf("ulimit -n 4096",
                "${CommonUtils.iptables} -t nat -F OUTPUT")
        val http_sb: MutableList<String> = arrayListOf()

        val cmd_bypass = "${CommonUtils.iptables}$CMD_IPTABLES_RETURN"

        if (InetAddress.getByName(connProfile!!.server.toUpperCase()) !is Inet6Address)
        {
            init_sb.add(cmd_bypass.replace("-p tcp -d 0.0.0.0", "-d ${connProfile!!.server}"))
        }
        init_sb.add(cmd_bypass.replace("-p tcp -d 0.0.0.0", "-d 127.0.0.1"))
        init_sb.add(cmd_bypass.replace("-p tcp -d 0.0.0.0", "-m owner --uid-owner $myUid"))
        init_sb.add(cmd_bypass.replace("-d 0.0.0.0", "--dport 53"))
        init_sb.add(
                "${CommonUtils.iptables} -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:8153")

        if (connProfile!!.globalProxy)
        {
            http_sb.add("${CommonUtils.iptables}$CMD_IPTABLES_DNAT_ADD_SOCKS")
        }
        else
        {
            val pm = packageManager
            connProfile!!.proxyApps.forEach({ app ->
                try
                {
                    val ai = pm.getApplicationInfo(app, PackageManager.GET_ACTIVITIES)
                    http_sb.add(("${CommonUtils.iptables}$CMD_IPTABLES_DNAT_ADD_SOCKS")
                            .replace("-t nat", "-t nat -m owner --uid-owner ${ai.uid}"))
                }
                catch(ignored: Exception)
                {
                }
            })
        }
        //
        init_sb.forEach {
            Log.e("EXC", it)
        }
        http_sb.forEach {
            Log.e("EXC", it)
        }
        //
        ShellUtil().runRootCmd(init_sb.toTypedArray())
        ShellUtil().runRootCmd(http_sb.toTypedArray())
    }

    private fun startRunner()
    {
        killProcesses()
        //
        Thread({
            if (!InetAddressUtil().isIPv4Address(connProfile!!.server) &&
                    !InetAddressUtil().isIPv6Address(connProfile!!.server))
            {
                val du: DNSUtil = DNSUtil()
                var ip = du.resolve(connProfile!!.server, true)
                if (ip == null)
                {
                    stopRunner()
                    if (callback != null)
                    {
                        try
                        {
                            callback!!.onStatusChanged(Consts.STATUS_FAILED)
                        }
                        catch (ignored: Exception)
                        {
                        }
                    }
                    return@Thread
                }
                connProfile!!.server = ip
            }
            //
            startSSRLocal()
            startTunnel()
            if (!connProfile!!.dnsForward)
            {
                startDnsDaemon()
            }
            startRedsocksDaemon()
            setupIptables()
            //
            flushDns()
            //
            val open = PendingIntent.getActivity(this@SSRNatService, -1,
                    Intent(this@SSRNatService, MainActivity::class.java), 0)
            val notificationBuilder = NotificationCompat.Builder(this@SSRNatService)
            notificationBuilder
                    .setWhen(0)
                    .setColor(ContextCompat.getColor(this@SSRNatService,
                            R.color.material_accent_500))
                    .setTicker("Nat service started")
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(connProfile!!.label)
                    .setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN)
                    .setSmallIcon(R.drawable.ic_stat_shadowsocks)
            startForeground(1, notificationBuilder.build())
            //
            isNATConnected = true
            if (callback != null)
            {
                try
                {
                    callback!!.onStatusChanged(Consts.STATUS_CONNECTED)
                }
                catch (ignored: Exception)
                {
                }
            }
        }).start()
    }

    private fun stopRunner()
    {
        killProcesses()
        isNATConnected = false

        if (callback == null)
        {
            stopSelf()
        }
        ShellUtil().runCmd(arrayOf("rm -f ${Consts.baseDir}pdnsd-nat.conf",
                "rm -f ${Consts.baseDir}redsocks-nat.conf"))
        stopForeground(true)
    }

    override fun onDestroy()
    {
        stopRunner()
        super.onDestroy()
    }
}
