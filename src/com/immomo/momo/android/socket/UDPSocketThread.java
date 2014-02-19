package com.immomo.momo.android.socket;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.util.Log;

import com.immomo.momo.android.BaseApplication;
import com.immomo.momo.android.entity.Message;
import com.immomo.momo.android.entity.NearByPeople;

public class UDPSocketThread implements Runnable {

    private static UDPSocketThread instance; // 唯一实例

    private static final String TAG = "SZU_UDPSocketThread";
    private static final String BROADCASTIP = "255.255.255.255"; // 广播地址
    private static final int BUFFERLENGTH = 1024; // 缓冲大小

    private byte[] receiveBuffer = new byte[BUFFERLENGTH];
    private byte[] sendBuffer = new byte[BUFFERLENGTH];

    private static BaseApplication mBaseApplication;
    private boolean isThreadRunning;
    private Thread receiveUDPThread; // 接收UDP数据线程

    private DatagramSocket UDPSocket;
    private DatagramPacket sendDatagramPacket;
    private DatagramPacket receiveDatagramPacket;

    private String mIMEI;
    private NearByPeople mNearByPeople; // 本机用户类

    /** 初始化相关参数 **/
    private UDPSocketThread() {
        mBaseApplication.OnlineUsers = new LinkedHashMap<String, NearByPeople>(); // 在线列表
        mBaseApplication.ReceiveMessages = new ConcurrentLinkedQueue<Message>(); // 消息队列
        mBaseApplication.mReceiveMsgListener = new Vector<OnReceiveMsgListener>(); // ReceiveMsgListener容器
        mBaseApplication.LastMsgCache = new HashMap<String, String>(); // 最后一条消息，KEY为IMEI
    }

    /**
     * <p>
     * 获取UDPSocketThread实例
     * <p>
     * 单例模式，返回唯一实例
     * 
     * @param paramApplication
     * @return instance
     */
    public static UDPSocketThread getInstance(BaseApplication paramApplication) {
        mBaseApplication = paramApplication;
        if (instance == null) {
            instance = new UDPSocketThread();
        }
        return instance;
    }

    @Override
    public void run() {
        while (isThreadRunning) {
            try {
                UDPSocket.receive(receiveDatagramPacket);
            }
            catch (IOException e) {
                isThreadRunning = false;
                receiveDatagramPacket = null;

                if (UDPSocket != null) {
                    UDPSocket.close();
                    UDPSocket = null;
                }

                receiveUDPThread = null;
                Log.e(TAG, "UDP数据包接收失败！线程停止");
                e.printStackTrace();
                break;
            }

            if (receiveDatagramPacket.getLength() == 0) {
                Log.i(TAG, "无法接收UDP数据或者接收到的UDP数据为空");
                continue;
            }

            String UDPListenResStr = ""; // 清空监听接收到的数据
            try {
                UDPListenResStr = new String(receiveBuffer, 0, receiveDatagramPacket.getLength(),
                        "gbk");
            }
            catch (UnsupportedEncodingException e) {
                Log.e(TAG, "系统不支持GBK编码");
            }
            Log.i(TAG, "接收到的UDP数据内容为:" + UDPListenResStr);

            IPMSGProtocol ipmsgRes = new IPMSGProtocol(UDPListenResStr);
            int commandNo = ipmsgRes.getCommandNo(); // 获取命令字

            if (!(mIMEI.equals(ipmsgRes.getSenderIMEI()))) { // 过滤自己发送的广播

                switch (0x000000FF & (ipmsgRes.getCommandNo())) {

                // 收到上线数据包，添加用户，并回送IPMSG_ANSENTRY应答。
                    case IPMSGConst.IPMSG_BR_ENTRY: {
                        Log.i(TAG, "收到上线通知");
                        addUser(ipmsgRes); // 增加用户至在线列表
                        // MyFeiGeBaseActivity.sendEmptyMessage(IpMessageConst.IPMSG_BR_ENTRY);

                        // 构造上线应答报文内容
                        IPMSGProtocol mIPMSGSend = new IPMSGProtocol();
                        mIPMSGSend.setSenderIMEI(mIMEI);
                        mIPMSGSend.setCommandNo(IPMSGConst.IPMSG_ANSENTRY);
                        mIPMSGSend.setAddObject(mNearByPeople);

                        sendUDPdata(mIPMSGSend.getProtocolJSON(),
                                receiveDatagramPacket.getAddress(), receiveDatagramPacket.getPort());
                        Log.i(TAG, "成功发送上线应答");
                    }
                        break;

                    // 收到上线应答，更新在线用户列表
                    case IPMSGConst.IPMSG_ANSENTRY: {
                        Log.i(TAG, "收到上线应答");
                        addUser(ipmsgRes); // 增加用户至在线列表
                        // MyFeiGeBaseActivity.sendEmptyMessage(IpMessageConst.IPMSG_ANSENTRY);
                    }
                        break;

                    // 收到下线广播
                    case IPMSGConst.IPMSG_BR_EXIT: {
                        String imei = ipmsgRes.getSenderIMEI();
                        mBaseApplication.OnlineUsers.remove(imei); // 移除用户
                        // MyFeiGeBaseActivity.sendEmptyMessage(IpMessageConst.IPMSG_BR_EXIT);

                        Log.i(TAG, "根据下线报文成功删除imei为" + imei + "的用户");
                    }
                        break;

                    // 拒绝接受文件
                    case IPMSGConst.IPMSG_RELEASEFILES: {
                        // MyFeiGeBaseActivity.sendEmptyMessage(IpMessageConst.IPMSG_RELEASEFILES);
                    }
                        break;

                } // End of switch

                // 每次接收完UDP数据后，重置长度。否则可能会导致下次收到数据包被截断。
                if (receiveDatagramPacket != null) {
                    receiveDatagramPacket.setLength(BUFFERLENGTH);
                }
            }
        }

        receiveDatagramPacket = null;
        if (UDPSocket != null) {
            UDPSocket.close();
            UDPSocket = null;
        }
        receiveUDPThread = null;

    }

    /** 建立Socket连接 **/
    public void connectUDPSocket() {
        try {
            // 绑定端口
            if (UDPSocket == null)
                UDPSocket = new DatagramSocket(IPMSGConst.PORT);
            Log.i(TAG, "connectUDPSocket() 绑定端口成功");

            // 创建数据接受包
            if (receiveDatagramPacket == null)
                receiveDatagramPacket = new DatagramPacket(receiveBuffer, BUFFERLENGTH);
            Log.i(TAG, "connectUDPSocket() 创建数据接收包成功");

            startUDPSocketThread();
        }
        catch (SocketException e) {
            e.printStackTrace();
        }
    }

    /** 开始监听线程 **/
    public void startUDPSocketThread() {
        if (receiveUDPThread == null) {
            receiveUDPThread = new Thread(this);
            receiveUDPThread.start();
        }
        isThreadRunning = true;
        Log.i(TAG, "startUDPSocketThread() 线程启动成功");
    }

    /** 暂停监听线程 **/
    public void stopUDPSocketThread() {
        if (receiveUDPThread != null)
            receiveUDPThread.interrupt();
        isThreadRunning = false;
        Log.i(TAG, "stopUDPSocketThread() 线程停止成功");
    }

    /** 用户上线通知 **/
    public void notifyOnline() {
        mIMEI = mBaseApplication.getIMEI();
        String device = mBaseApplication.getDevice();
        String nickname = mBaseApplication.getNickname();
        String gender = mBaseApplication.getGender();
        String localIPaddress = mBaseApplication.getLocalIPaddress();
        String constellation = mBaseApplication.getConstellation();
        String logintime = mBaseApplication.getLoginTime();
        int avatar = mBaseApplication.getAvatar();
        int age = mBaseApplication.getAge();
        mNearByPeople = new NearByPeople(mIMEI, avatar, device, nickname, gender, age,
                constellation, localIPaddress, logintime);

        IPMSGProtocol ipmsgProtocol = new IPMSGProtocol();
        ipmsgProtocol.setSenderIMEI(mIMEI);
        ipmsgProtocol.setCommandNo(IPMSGConst.IPMSG_BR_ENTRY); // 上线命令
        ipmsgProtocol.setAddObject(mNearByPeople);

        InetAddress broadcastAddr;
        try {
            broadcastAddr = InetAddress.getByName(BROADCASTIP);
            sendUDPdata(ipmsgProtocol.getProtocolJSON(), broadcastAddr, IPMSGConst.PORT);
        }
        catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "notifyOnline() 广播地址有误");
        }
    }

    /** 用户下线通知 **/
    public void notifyOffline() {
        IPMSGProtocol mIpmsgProtocol = new IPMSGProtocol();
        mIpmsgProtocol.setSenderIMEI(mIMEI);
        mIpmsgProtocol.setCommandNo(IPMSGConst.IPMSG_BR_EXIT); // 下线命令

        InetAddress broadcastAddr;
        try {
            broadcastAddr = InetAddress.getByName(BROADCASTIP); // 广播地址
            sendUDPdata(mIpmsgProtocol.getProtocolJSON(), broadcastAddr, IPMSGConst.PORT);
        }
        catch (UnknownHostException e) {
            e.printStackTrace();
            Log.e(TAG, "notifyOffline()....广播地址有误");
        }
    }

    /** 添加listener到容器中 **/
    public void addReceiveMsgListener(OnReceiveMsgListener paramListener) {
        if (!(mBaseApplication.mReceiveMsgListener.contains(paramListener))) {
            mBaseApplication.mReceiveMsgListener.add(paramListener);
        }
    }

    /** 从容器中移除相应listener **/
    public void removeReceiveMsgListener(OnReceiveMsgListener paramListener) {
        if (mBaseApplication.mReceiveMsgListener.contains(paramListener)) {
            mBaseApplication.mReceiveMsgListener.remove(paramListener);
        }
    }

    /**
     * 判断是否有已打开的聊天窗口来接收对应的数据。
     * 
     * @param paramMsg
     * @return
     */
    private boolean isActivityActive(Message paramMsg) {
        int mLength = mBaseApplication.mReceiveMsgListener.size();
        for (int i = 0; i < mLength; i++) {
            OnReceiveMsgListener listener = mBaseApplication.mReceiveMsgListener.get(i);
            if (listener.isThisActivityMsg(paramMsg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发送Socket UDP数据包
     * 
     * @param sendStr
     *            发送内容
     * @param targetIP
     *            发送IP
     * @param sendPort
     *            通信端口
     */
    public synchronized void sendUDPdata(String sendStr, InetAddress targetIP, int sendPort) {
        try {
            sendBuffer = sendStr.getBytes("gbk");
            sendDatagramPacket = new DatagramPacket(sendBuffer, sendBuffer.length, targetIP,
                    sendPort);
            Log.i(TAG, "sendDatagramPacket 创建成功");
            UDPSocket.send(sendDatagramPacket);
            Log.i(TAG, "sendUDPdata() 数据发送成功");
        }
        catch (Exception e) {
            Log.e(TAG, "sendUDPdata() 发送UDP数据包失败");
            e.printStackTrace();
        }
        finally {
            sendDatagramPacket = null;
        }
    }

    /** 刷新用户列表 **/
    public void refreshUsers() {
        mBaseApplication.OnlineUsers.clear(); // 清空在线用户列表
        notifyOnline(); // 发送上线通知
        // MyFeiGeBaseActivity.sendEmptyMessage(IpMessageConst.IPMSG_BR_ENTRY);
    }

    /**
     * 添加用户到在线列表中 (线程安全的)
     * 
     * @param paramIPMSGProtocol
     *            包含用户信息的IPMSGProtocol字符串
     */
    private synchronized void addUser(IPMSGProtocol paramIPMSGProtocol) {
        String receiveIMEI = paramIPMSGProtocol.getSenderIMEI();
        if (!(mIMEI.equals(receiveIMEI))) {
            NearByPeople newUser = (NearByPeople) paramIPMSGProtocol.getAddObject();
            mBaseApplication.OnlineUsers.put(receiveIMEI, newUser);
            Log.i(TAG, "成功添加imei为" + receiveIMEI + "的用户");
        }
    }

    /** 接收消息监听的listener接口 **/
    public interface OnReceiveMsgListener {

        /**
         * 判断收到的消息是否匹配已打开的聊天窗口
         * 
         * @param paramMsg
         *            收到的消息对象
         * @return
         */
        public boolean isThisActivityMsg(Message paramMsg);
    }

}