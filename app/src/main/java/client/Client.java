/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation version 3 as published by
the Free Software Foundation. You may not use, modify or distribute
this program under any other version of the GNU Affero General Public
License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package client;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import client.inventory.InventoryType;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.id.MapId;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import net.PacketHandler;
import net.PacketProcessor;
import net.netty.InvalidPacketHeaderException;
import net.packet.InPacket;
import net.packet.Packet;
import net.net.logging.LoggingUtil;
import net.net.logging.MonitoredChrLogger;
import net.server.Server;
import net.server.channel.Channel;
import net.server.coordinator.login.LoginBypassCoordinator;
import net.server.coordinator.session.Hwid;
import net.server.coordinator.session.SessionCoordinator;
import net.server.coordinator.session.SessionCoordinator.AntiMulticlientResult;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;
import net.server.world.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.AbstractPlayerInteraction;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import scripting.npc.NPCConversationManager;
import scripting.npc.NPCScriptManager;
import scripting.quest.QuestActionManager;
import scripting.quest.QuestScriptManager;
import server.MapleLeafLogger;
import server.ThreadManager;
import server.TimerManager;
import server.life.Monster;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.maps.MiniDungeonInfo;
import tools.BCrypt;
import tools.DatabaseConnection;
import tools.HexTool;
import tools.PacketCreator;

import javax.script.ScriptEngine;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.SECONDS;

public class Client extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Client.class);

    public static final int LOGIN_NOTLOGGEDIN = 0;
    public static final int LOGIN_SERVER_TRANSITION = 1;
    public static final int LOGIN_LOGGEDIN = 2;

    private final Type type;
    private final long sessionId;
    private final PacketProcessor packetProcessor;

    private Hwid hwid;
    private String remoteAddress;
    private volatile boolean inTransition;
    private io.netty.channel.Channel ioChannel;
    private Character player;
    private int channel = 1;
    private int accId = -4;
    private boolean loggedIn = false;
    private boolean serverTransition = false;
    private Calendar birthday = null;
    private String accountName = null;
    private int world;
    private volatile long lastPong;
    private int gmlevel;
    private Set<String> macs = new HashSet<>();
    private Map<String, ScriptEngine> engines = new HashMap<>();
    private byte characterSlots = 3;
    private byte loginattempt = 0;
    private String pin = "";
    private int pinattempt = 0;
    private String pic = "";
    private int picattempt = 0;
    private byte csattempt = 0;
    private byte gender = -1;
    private boolean disconnecting = false;
    private final Semaphore actionsSemaphore = new Semaphore(7);
    private final Lock lock = new ReentrantLock(true);
    private final Lock encoderLock = new ReentrantLock(true);
    private final Lock announcerLock = new ReentrantLock(true);
    // thanks Masterrulax & try2hack for pointing out a bottleneck issue with shared locks, shavit for noticing an opportunity for improvement
    private Calendar tempBanCalendar;
    private int votePoints;
    private int voteTime = -1;
    private int visibleWorlds;
    private long lastNpcClick;
    private long lastPacket = System.currentTimeMillis();
    private int lang = 0;
    private Context context;

    public enum Type {
        LOGIN,
        CHANNEL
    }

    public Client(Type type, long sessionId, String remoteAddress, PacketProcessor packetProcessor, int world, int channel, Context context) {
        this.type = type;
        this.sessionId = sessionId;
        this.remoteAddress = remoteAddress;
        this.packetProcessor = packetProcessor;
        this.world = world;
        this.channel = channel;
        this.context = context;
    }

    public static Client createLoginClient(long sessionId, String remoteAddress, PacketProcessor packetProcessor,
                                           int world, int channel, Context context) {
        return new Client(Type.LOGIN, sessionId, remoteAddress, packetProcessor, world, channel, context);
    }

    public static Client createChannelClient(long sessionId, String remoteAddress, PacketProcessor packetProcessor,
                                             int world, int channel, Context context) {
        return new Client(Type.CHANNEL, sessionId, remoteAddress, packetProcessor, world, channel, context);
    }

    public static Client createMock(Context context) {
        return new Client(null, -1, null, null, -123, -123, context);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        final io.netty.channel.Channel channel = ctx.channel();
        if (!Server.getInstance().isOnline()) {
            channel.close();
            return;
        }

        this.remoteAddress = getRemoteAddress(channel);
        this.ioChannel = channel;
    }

    private static String getRemoteAddress(io.netty.channel.Channel channel) {
        String remoteAddress = "null";
        try {
            remoteAddress = ((InetSocketAddress) channel.remoteAddress()).getAddress().getHostAddress();
        } catch (NullPointerException npe) {
            log.warn("Unable to get remote address for client", npe);
        }

        return remoteAddress;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof InPacket packet)) {
            log.warn("Received invalid message: {}", msg);
            return;
        }

        short opcode = packet.readShort();
        final PacketHandler handler = packetProcessor.getHandler(opcode);

        if (YamlConfig.config.server.USE_DEBUG_SHOW_RCVD_PACKET && !LoggingUtil.isIgnoredRecvPacket(opcode)) {
            log.debug("Received packet id {}", String.valueOf(opcode));
        }

        if (handler != null && handler.validateState(this)) {
            try {
                MonitoredChrLogger.logPacketIfMonitored(this, opcode, packet.getBytes());
                handler.handlePacket(packet, this);
            } catch (final Throwable t) {
                final String chrInfo = player != null ? player.getName() + " on map " + player.getMapId() : "?";
                log.warn("Error in packet handler {}. Chr {}, account {}. Packet: {}", handler.getClass().getSimpleName(),
                        chrInfo, getAccountName(), packet, t);
                //client.sendPacket(PacketCreator.enableActions());//bugs sometimes
            }
        }

        updateLastPacket();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent idleEvent) {
            checkIfIdle(idleEvent);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (player != null) {
            log.warn("Exception caught by {}", player, cause);
        }

        if (cause instanceof InvalidPacketHeaderException) {
            SessionCoordinator.getInstance().closeSession(this, true);
        } else if (cause instanceof IOException) {
            closeMapleSession();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeMapleSession();
    }

    private void closeMapleSession() {
        switch (type) {
            case LOGIN -> SessionCoordinator.getInstance().closeLoginSession(this);
            case CHANNEL -> SessionCoordinator.getInstance().closeSession(this, null);
        }

        try {
            // client freeze issues on session transition states found thanks to yolinlin, Omo Oppa, Nozphex
            if (!inTransition) {
                disconnect(false, false);
            }
        } catch (Throwable t) {
            log.warn("Account stuck", t);
        } finally {
            closeSession();
        }
    }

    public void updateLastPacket() {
        lastPacket = System.currentTimeMillis();
    }

    public long getLastPacket() {
        return lastPacket;
    }

    public void closeSession() {
        ioChannel.close();
    }

    public void disconnectSession() {
        ioChannel.disconnect();
    }

    public Hwid getHwid() {
        return hwid;
    }

    public void setHwid(Hwid hwid) {
        this.hwid = hwid;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public boolean isInTransition() {
        return inTransition;
    }

    public EventManager getEventManager(String event) {
        return getChannelServer().getEventSM().getEventManager(event);
    }

    public Character getPlayer() {
        return player;
    }

    public void setPlayer(Character player) {
        this.player = player;
    }

    public AbstractPlayerInteraction getAbstractPlayerInteraction() {
        return new AbstractPlayerInteraction(this);
    }

    public void sendCharList(int server) {
        this.sendPacket(PacketCreator.getCharList(this, server, 0));
    }

    public List<Character> loadCharacters(int serverId) {
        List<Character> chars = new ArrayList<>(15);
        try {
            for (CharNameAndId cni : loadCharactersInternal(serverId)) {
                chars.add(Character.loadCharFromDB(cni.id, this, false));
            }
        } catch (Exception e) {
            log.error("loadCharacters error", e);
        }
        return chars;
    }

    public List<String> loadCharacterNames(int worldId) {
        List<String> chars = new ArrayList<>(15);
        for (CharNameAndId cni : loadCharactersInternal(worldId)) {
            chars.add(cni.name);
        }
        return chars;
    }

    private List<CharNameAndId> loadCharactersInternal(int worldId) {
        List<CharNameAndId> chars = new ArrayList<>(15);
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor ps = con.rawQuery("SELECT id, name FROM characters WHERE accountid = ? AND world = ?", new String[]{String.valueOf(this.getAccID()), String.valueOf(worldId)})) {
            if (ps.moveToFirst()) {
                do {
                    int idIdx = ps.getColumnIndex("id");
                    int nameIdx = ps.getColumnIndex("name");
                    if (idIdx != -1 && nameIdx != -1) {
                        int id = ps.getInt(idIdx);
                        String name = ps.getString(nameIdx);
                        chars.add(new CharNameAndId(name, id));
                    }
                } while (ps.moveToNext());
            }

        } catch (SQLiteException e) {
            log.error("loadCharactersInternal error", e);
        }
        return chars;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public boolean hasBannedIP() {
        boolean ret = false;
        SQLiteDatabase con = DatabaseConnection.getConnection();
        String query = "SELECT COUNT(*) FROM ipbans WHERE ? LIKE ip || '%'";

        try (Cursor cursor = con.rawQuery(query, new String[]{remoteAddress})) {
            if (cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                if (count > 0) {
                    ret = true;
                }
            }
        } catch (SQLiteException e) {
            log.error("hasBannedIP Error", e);
        }
        return ret;
    }

    public int getVoteTime() {
        if (voteTime != -1) {
            return voteTime;
        }
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor ps = con.rawQuery("SELECT date FROM bit_votingrecords WHERE UPPER(account) = UPPER(?)", new String[]{accountName.toUpperCase()})) {
            if (ps.moveToFirst()) {
                int dateIdx = ps.getColumnIndex("date");
                if (dateIdx != -1) {
                    voteTime = ps.getInt(dateIdx);
                }
            }
        } catch (SQLiteException e) {
            log.error("Error getting voting time");
            return -1;
        }
        return voteTime;
    }

    public void resetVoteTime() {
        voteTime = -1;
    }

    public boolean hasVotedAlready() {
        Date currentDate = new Date();
        int timeNow = (int) (currentDate.getTime() / 1000);
        int difference = (timeNow - getVoteTime());
        return difference < 86400 && difference > 0;
    }

    public boolean hasBannedHWID() {
        if (hwid == null) {
            return false;
        }

        boolean ret = false;
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor ps = con.rawQuery("SELECT COUNT(*) FROM hwidbans WHERE hwid LIKE ?", new String[]{hwid.hwid()})) {
            if (ps.moveToFirst()) {
                if (ps.getInt(0) > 0) {
                    ret = true;
                }
            }
        } catch (SQLiteException e) {
            log.error("hasBannedHWID error", e);
        }

        return ret;
    }

    public boolean hasBannedMac() {
        if (macs.isEmpty()) {
            return false;
        }
        boolean ret = false;
        int i;
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM macbans WHERE mac IN (");
        for (i = 0; i < macs.size(); i++) {
            sql.append("?");
            if (i != macs.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append(")");
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.rawQuery(sql.toString(), macs.toArray(new String[0]))) {
            if (cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                if (count > 0) {
                    ret = true;
                }
            }
        } catch (SQLiteException e) {
            log.error("hasBannedMac error", e);
        }
        return ret;
    }


    private void loadHWIDIfNescessary() throws SQLiteException {
        if (hwid == null) {
            SQLiteDatabase con = DatabaseConnection.getConnection();
            try (Cursor cursor = con.rawQuery("SELECT hwid FROM accounts WHERE id = ?", new String[]{String.valueOf(accId)})) {
                if (cursor.moveToFirst()) {
                    int hwidIdx = cursor.getColumnIndex("hwid");
                    hwid = new Hwid(cursor.getString(hwidIdx));
                }
            }
        }
    }

    // TODO: Recode to close statements...
    private void loadMacsIfNescessary() throws SQLiteException {
        if (macs.isEmpty()) {
            SQLiteDatabase con = DatabaseConnection.getConnection();
            try (Cursor cursor = con.rawQuery("SELECT macs FROM accounts WHERE id = ?", new String[]{String.valueOf(accId)})) {
                if (cursor.moveToFirst()) {
                    int macsIdx = cursor.getColumnIndex("macs");
                    if (macsIdx != -1) {
                        String macsString = cursor.getString(macsIdx);
                        for (String mac : macsString.split(", ")) {
                            if (!mac.equals("")) {
                                macs.add(mac);
                            }
                        }
                    }
                }
            }
        }
    }

    public void banHWID() {
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            loadHWIDIfNescessary();
            ContentValues values = new ContentValues();
            values.put("hwid", hwid.hwid());
            con.insert("hwidbans", null, values);
        } catch (SQLiteException e) {
            log.error("banHWID error", e);
        }
    }

    public void banMacs() {
        loadMacsIfNescessary();
        List<String> filtered = new LinkedList<>();
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.rawQuery("SELECT filter FROM macfilters", null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    int filterIdx = cursor.getColumnIndex("filter");
                    if (filterIdx != -1) {
                        String filter = cursor.getString(filterIdx);
                        filtered.add(filter);
                    }
                }
            }
        } catch (SQLiteException e) {
            log.error("banMacs error", e);
        }

        for (String mac : macs) {
            boolean matched = false;
            for (String filter : filtered) {
                if (mac.matches(filter)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                ContentValues values = new ContentValues();
                values.put("mac", mac);
                values.put("aid", String.valueOf(getAccID()));
                con.insert("macbans", null, values);
            }
        }
    }

    public int finishLogin() {
        encoderLock.lock();
        try {
            if (getLoginState() > LOGIN_NOTLOGGEDIN) { // 0 = LOGIN_NOTLOGGEDIN, 1= LOGIN_SERVER_TRANSITION, 2 = LOGIN_LOGGEDIN
                loggedIn = false;
                return 7;
            }
            updateLoginState(Client.LOGIN_LOGGEDIN);
        } finally {
            encoderLock.unlock();
        }

        return 0;
    }

    public void setPin(String pin) {
        this.pin = pin;
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            ContentValues values = new ContentValues();
            values.put("pin", pin);
            String whereClause = "id=?";
            String[] whereArgs = { String.valueOf(accId) };
            con.update("accounts", values, whereClause, whereArgs);
        } catch (SQLiteException e) {
            log.error("setPin error", e);
        }
    }

    public String getPin() {
        return pin;
    }

    public boolean checkPin(String other) {
        if (!(YamlConfig.config.server.ENABLE_PIN && !canBypassPin())) {
            return true;
        }

        pinattempt++;
        if (pinattempt > 5) {
            SessionCoordinator.getInstance().closeSession(this, false);
        }
        if (pin.equals(other)) {
            pinattempt = 0;
            LoginBypassCoordinator.getInstance().registerLoginBypassEntry(hwid, accId, false);
            return true;
        }
        return false;
    }

    public void setPic(String pic) {
        this.pic = pic;
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            ContentValues values = new ContentValues();
            values.put("pic", pic);
            String whereClause = "id=?";
            String[] whereArgs = { String.valueOf(accId) };
            con.update("accounts", values, whereClause, whereArgs);
        } catch (SQLiteException e) {
            log.error("setPic error", e);
        }
    }

    public String getPic() {
        return pic;
    }

    public boolean checkPic(String other) {
        if (!(YamlConfig.config.server.ENABLE_PIC && !canBypassPic())) {
            return true;
        }

        picattempt++;
        if (picattempt > 5) {
            SessionCoordinator.getInstance().closeSession(this, false);
        }
        if (pic.equals(other)) {    // thanks ryantpayton (HeavenClient) for noticing null pics being checked here
            picattempt = 0;
            LoginBypassCoordinator.getInstance().registerLoginBypassEntry(hwid, accId, true);
            return true;
        }
        return false;
    }

    public int login(String login, String pwd, Hwid hwid) {
        int loginok = 5;

        loginattempt++;
        if (loginattempt > 4) {
            loggedIn = false;
            SessionCoordinator.getInstance().closeSession(this, false);
            return 6;   // thanks Survival_Project for finding out an issue with AUTOMATIC_REGISTER here
        }

        String[] columns = {"id", "password", "gender", "banned", "pin", "pic", "characterslots", "tos", "language"};
        String selection = "name = ?";
        String[] selectionArgs = {login};
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.query("accounts", columns, selection, selectionArgs, null, null, null)) {
            accId = -2;
            if (cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndex("id");
                if (idIdx != -1) {
                    accId = cursor.getInt(idIdx);
                    if (accId <= 0) {
                        log.warn("Tried to log in with accId {}", accId);
                        return 15;
                    }
                }

                int bannedIdx = cursor.getColumnIndex("banned");
                int pinIdx = cursor.getColumnIndex("pin");
                int picIdx = cursor.getColumnIndex("pic");
                int genderIdx = cursor.getColumnIndex("gender");
                int characterslotsIdx = cursor.getColumnIndex("characterslots");
                int languageIdx = cursor.getColumnIndex("language");
                int passwordIdx = cursor.getColumnIndex("password");
                int tosIdx = cursor.getColumnIndex("tos");

                int banned = 1;
                String passhash = "";
                byte tos = 0;
                if (bannedIdx != -1 && pinIdx != -1 && picIdx != -1 && genderIdx != -1 && characterslotsIdx != -1 && languageIdx != -1 && passwordIdx != -1 && tosIdx != -1) {
                    banned = cursor.getInt(bannedIdx);
                    gmlevel = 0;
                    pin = cursor.getString(pinIdx);
                    pic = cursor.getString(picIdx);
                    gender = (byte) cursor.getInt(genderIdx);
                    characterSlots = (byte) cursor.getInt(characterslotsIdx);
                    lang = cursor.getInt(languageIdx);
                    passhash = cursor.getString(passwordIdx);
                    tos = (byte) cursor.getInt(tosIdx);
                }
                if (banned == 1) {
                    return 3;
                }
                if (getLoginState() > LOGIN_NOTLOGGEDIN) { // already loggedin
                    loggedIn = false;
                    loginok = 7;
                } else if (passhash.charAt(0) == '$' && passhash.charAt(1) == '2' && BCrypt.checkpw(pwd, passhash)) {
                    loginok = (tos == 0) ? 23 : 0;
                } else if (pwd.equals(passhash) || checkHash(passhash, "SHA-1", pwd) || checkHash(passhash, "SHA-512", pwd)) {
                    // thanks GabrielSin for detecting some no-bcrypt inconsistencies here
                    loginok = (tos == 0) ? (!YamlConfig.config.server.BCRYPT_MIGRATION ? 23 : -23) : (!YamlConfig.config.server.BCRYPT_MIGRATION ? 0 : -10); // migrate to bcrypt
                } else {
                    loggedIn = false;
                    loginok = 4;
                }
            } else {
                accId = -3;
            }
        } catch (SQLiteException e) {
            log.error("Query accounts error", e);
        }

        if (loginok == 0 || loginok == 4) {
            AntiMulticlientResult res = SessionCoordinator.getInstance().attemptLoginSession(this, hwid, accId, loginok == 4);

            switch (res) {
                case SUCCESS:
                    if (loginok == 0) {
                        loginattempt = 0;
                    }
                    return loginok;
                case REMOTE_LOGGEDIN:
                    return 17;
                case REMOTE_REACHED_LIMIT:
                    return 13;
                case REMOTE_PROCESSING:
                    return 10;
                case MANY_ACCOUNT_ATTEMPTS:
                    return 16;
                default:
                    return 8;
            }
        } else {
            return loginok;
        }
    }

    public Calendar getTempBanCalendarFromDB() {
        final Calendar lTempban = Calendar.getInstance();
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.rawQuery("SELECT `tempban` FROM accounts WHERE id = ?", new String[]{String.valueOf(getAccID())})) {
            if (cursor.moveToFirst()) {
                int tempbanIdx = cursor.getColumnIndex("tempban");
                if (tempbanIdx != -1) {
                    long tempbanTimestamp = cursor.getLong(tempbanIdx);
                    Calendar tempbanCalendar = Calendar.getInstance();
                    tempbanCalendar.setTimeInMillis(tempbanTimestamp);
                    int year = tempbanCalendar.get(Calendar.YEAR);
                    int month = tempbanCalendar.get(Calendar.MONTH);
                    int dayOfMonth = tempbanCalendar.get(Calendar.DAY_OF_MONTH);
                    int hour = tempbanCalendar.get(Calendar.HOUR_OF_DAY);
                    int minute = tempbanCalendar.get(Calendar.MINUTE);
                    int second = tempbanCalendar.get(Calendar.SECOND);

                    LocalDateTime tempbanLocal = LocalDateTime.of(year, month + 1, dayOfMonth, hour, minute, second);
                    if (tempbanLocal.equals(DefaultDates.getTempban())) {
                        return null;
                    }
                    lTempban.setTimeInMillis(tempbanTimestamp);
                    tempBanCalendar = lTempban;
                    return lTempban;
                }
            }
        } catch (SQLiteException e) {
            log.error("getTempBanCalendarFromDB error", e);
        }

        return null;//why oh why!?!
    }

    public Calendar getTempBanCalendar() {
        return tempBanCalendar;
    }

    public boolean hasBeenBanned() {
        return tempBanCalendar != null;
    }

    public static long dottedQuadToLong(String dottedQuad) throws RuntimeException {
        String[] quads = dottedQuad.split("\\.");
        if (quads.length != 4) {
            throw new RuntimeException("Invalid IP Address format.");
        }
        long ipAddress = 0;
        for (int i = 0; i < 4; i++) {
            int quad = Integer.parseInt(quads[i]);
            ipAddress += (long) (quad % 256) * (long) Math.pow(256, 4 - i);
        }
        return ipAddress;
    }

    public void updateHwid(Hwid hwid) {
        this.hwid = hwid;
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            ContentValues values = new ContentValues();
            values.put("hwid", hwid.hwid());
            String whereClause = "id=?";
            String[] whereArgs = { String.valueOf(accId) };
            con.update("accounts", values, whereClause, whereArgs);
        } catch (SQLiteException e) {
            log.error("updateHwid error", e);
        }
    }

    public void updateMacs(String macData) {
        macs.addAll(Arrays.asList(macData.split(", ")));
        StringBuilder newMacData = new StringBuilder();
        Iterator<String> iter = macs.iterator();
        while (iter.hasNext()) {
            String cur = iter.next();
            newMacData.append(cur);
            if (iter.hasNext()) {
                newMacData.append(", ");
            }
        }
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            ContentValues values = new ContentValues();
            values.put("macs", newMacData.toString());
            String whereClause = "id=?";
            String[] whereArgs = { String.valueOf(accId) };
            con.update("accounts", values, whereClause, whereArgs);
        } catch (SQLiteException e) {
            log.error("updateMacs error", e);
        }
    }

    public void setAccID(int id) {
        this.accId = id;
    }

    public int getAccID() {
        return accId;
    }

    public void updateLoginState(int newState) {
        // rules out possibility of multiple account entries
        if (newState == LOGIN_LOGGEDIN) {
            SessionCoordinator.getInstance().updateOnlineClient(this);
        }
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            ContentValues values = new ContentValues();
            values.put("loggedin", newState);
            values.put("lastlogin", String.valueOf(new Timestamp(Server.getInstance().getCurrentTime())));

            con.update("accounts", values, "id = ?", new String[]{String.valueOf(getAccID())});
        } catch (SQLiteException e) {
            log.error("updateLoginState error", e);
        }

        if (newState == LOGIN_NOTLOGGEDIN) {
            loggedIn = false;
            serverTransition = false;
            setAccID(0);
        } else {
            serverTransition = (newState == LOGIN_SERVER_TRANSITION);
            loggedIn = !serverTransition;
        }
    }

    public int getLoginState() {  // 0 = LOGIN_NOTLOGGEDIN, 1= LOGIN_SERVER_TRANSITION, 2 = LOGIN_LOGGEDIN
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.rawQuery("SELECT loggedin, lastlogin, birthday FROM accounts WHERE id = ?", new String[]{String.valueOf(getAccID())})) {
            int state = 0;
            if (cursor.moveToFirst()) {
                birthday = Calendar.getInstance();
                int birthdayIdx = cursor.getColumnIndex("birthday");
                if (birthdayIdx != -1) {
                    long birthdayMillis = cursor.getLong(birthdayIdx);
                    birthday.setTimeInMillis(birthdayMillis);
                }

                int loggedinIdx = cursor.getColumnIndex("loggedin");
                if (loggedinIdx != -1) {
                    state = cursor.getInt(loggedinIdx);
                }
                if (state == LOGIN_SERVER_TRANSITION) {
                    int lastloginIdx = cursor.getColumnIndex("lastlogin");
                    if (lastloginIdx != -1) {
                        String lastLoginMillis = cursor.getString(lastloginIdx);
                        Timestamp timestamp = Timestamp.valueOf(lastLoginMillis);
                        if (timestamp.getTime() + 30000 < Server.getInstance().getCurrentTime()) {
                            int accountId = accId;
                            state = LOGIN_NOTLOGGEDIN;
                            updateLoginState(Client.LOGIN_NOTLOGGEDIN);
                            this.setAccID(accountId);
                        }
                    }
                }
            } else {
                throw new RuntimeException("getLoginState - Client AccID: " + getAccID());
            }

            if (state == LOGIN_LOGGEDIN) {
                loggedIn = true;
            } else if (state == LOGIN_SERVER_TRANSITION) {
                String[] whereArgs = {String.valueOf(getAccID())};
                con.execSQL("UPDATE accounts SET loggedin = 0 WHERE id = ?", whereArgs);
            } else {
                loggedIn = false;
            }
            return state;
        } catch (SQLiteException e) {
            loggedIn = false;
            log.error("getLoginState error", e);
            throw new RuntimeException("login state");
        }
    }

    public boolean checkBirthDate(Calendar date) {
        return date.get(Calendar.YEAR) == birthday.get(Calendar.YEAR) && date.get(Calendar.MONTH) == birthday.get(Calendar.MONTH) && date.get(Calendar.DAY_OF_MONTH) == birthday.get(Calendar.DAY_OF_MONTH);
    }

    private void removePartyPlayer(World wserv) {
        MapleMap map = player.getMap();
        final Party party = player.getParty();
        final int idz = player.getId();

        if (party != null) {
            final PartyCharacter chrp = new PartyCharacter(player);
            chrp.setOnline(false);
            wserv.updateParty(party.getId(), PartyOperation.LOG_ONOFF, chrp);
            if (party.getLeader().getId() == idz && map != null) {
                PartyCharacter lchr = null;
                for (PartyCharacter pchr : party.getMembers()) {
                    if (pchr != null && pchr.getId() != idz && (lchr == null || lchr.getLevel() <= pchr.getLevel()) && map.getCharacterById(pchr.getId()) != null) {
                        lchr = pchr;
                    }
                }
                if (lchr != null) {
                    wserv.updateParty(party.getId(), PartyOperation.CHANGE_LEADER, lchr);
                }
            }
        }
    }

    private void removePlayer(World wserv, boolean serverTransition) {
        try {
            player.setDisconnectedFromChannelWorld();
            player.notifyMapTransferToPartner(-1);
            player.removeIncomingInvites();
            player.cancelAllBuffs(true);

            player.closePlayerInteractions();
            player.closePartySearchInteractions();

            if (!serverTransition) {    // thanks MedicOP for detecting an issue with party leader change on changing channels
                removePartyPlayer(wserv);

                EventInstanceManager eim = player.getEventInstance();
                if (eim != null) {
                    eim.playerDisconnected(player);
                }

                if (player.getMonsterCarnival() != null) {
                    player.getMonsterCarnival().playerDisconnected(getPlayer().getId());
                }

                if (player.getAriantColiseum() != null) {
                    player.getAriantColiseum().playerDisconnected(getPlayer());
                }
            }

            if (player.getMap() != null) {
                int mapId = player.getMapId();
                player.getMap().removePlayer(player);
                if (MapId.isDojo(mapId)) {
                    this.getChannelServer().freeDojoSectionIfEmpty(mapId);
                }
                
                if (player.getMap().getHPDec() > 0) {
                    getWorldServer().removePlayerHpDecrease(player);
                }
            }

        } catch (final Throwable t) {
            log.error("Account stuck", t);
        }
    }

    public final void disconnect(final boolean shutdown, final boolean cashshop) {
        if (canDisconnect()) {
            ThreadManager.getInstance().newTask(() -> disconnectInternal(shutdown, cashshop));
        }
    }

    public final void forceDisconnect() {
        if (canDisconnect()) {
            disconnectInternal(true, false);
        }
    }

    private synchronized boolean canDisconnect() {
        if (disconnecting) {
            return false;
        }

        disconnecting = true;
        return true;
    }

    private void disconnectInternal(boolean shutdown, boolean cashshop) {//once per Client instance
        if (player != null && player.isLoggedin() && player.getClient() != null) {
            final int messengerid = player.getMessenger() == null ? 0 : player.getMessenger().getId();
            //final int fid = player.getFamilyId();
            final BuddyList bl = player.getBuddylist();
            final MessengerCharacter chrm = new MessengerCharacter(player, 0);
            final GuildCharacter chrg = player.getMGC();
            final Guild guild = player.getGuild();

            player.cancelMagicDoor();

            final World wserv = getWorldServer();   // obviously wserv is NOT null if this player was online on it
            try {
                removePlayer(wserv, this.serverTransition);

                if (!(channel == -1 || shutdown)) {
                    if (!cashshop) {
                        if (!this.serverTransition) { // meaning not changing channels
                            if (messengerid > 0) {
                                wserv.leaveMessenger(messengerid, chrm);
                            }
                                                        /*      
                                                        if (fid > 0) {
                                                                final Family family = worlda.getFamily(fid);
                                                                family.
                                                        }
                                                        */

                            player.forfeitExpirableQuests();    //This is for those quests that you have to stay logged in for a certain amount of time

                            if (guild != null) {
                                final Server server = Server.getInstance();
                                server.setGuildMemberOnline(player, false, player.getClient().getChannel());
                                player.sendPacket(GuildPackets.showGuildInfo(player));
                            }
                            if (bl != null) {
                                wserv.loggedOff(player.getName(), player.getId(), channel, player.getBuddylist().getBuddyIds());
                            }
                        }
                    } else {
                        if (!this.serverTransition) { // if dc inside of cash shop.
                            if (bl != null) {
                                wserv.loggedOff(player.getName(), player.getId(), channel, player.getBuddylist().getBuddyIds());
                            }
                        }
                    }
                }
            } catch (final Exception e) {
                log.error("Account stuck", e);
            } finally {
                if (!this.serverTransition) {
                    if (chrg != null) {
                        chrg.setCharacter(null);
                    }
                    wserv.removePlayer(player);
                    //getChannelServer().removePlayer(player); already being done

                    player.saveCooldowns();
                    player.cancelAllDebuffs();
                    player.saveCharToDB(true);

                    player.logOff();
                    if (YamlConfig.config.server.INSTANT_NAME_CHANGE) {
                        player.doPendingNameChange();
                    }
                    clear();
                } else {
                    getChannelServer().removePlayer(player);

                    player.saveCooldowns();
                    player.cancelAllDebuffs();
                    player.saveCharToDB();
                }
            }
        }

        SessionCoordinator.getInstance().closeSession(this, false);

        if (!serverTransition && isLoggedIn()) {
            updateLoginState(Client.LOGIN_NOTLOGGEDIN);

            clear();
        } else {
            if (!Server.getInstance().hasCharacteridInTransition(this)) {
                updateLoginState(Client.LOGIN_NOTLOGGEDIN);
            }

            engines = null; // thanks Tochi for pointing out a NPE here
        }
    }

    private void clear() {
        // player hard reference removal thanks to Steve (kaito1410)
        if (this.player != null) {
            this.player.empty(true); // clears schedules and stuff
        }

        Server.getInstance().unregisterLoginState(this);

        this.accountName = null;
        this.macs = null;
        this.hwid = null;
        this.birthday = null;
        this.engines = null;
        this.player = null;
    }

    public void setCharacterOnSessionTransitionState(int cid) {
        this.updateLoginState(Client.LOGIN_SERVER_TRANSITION);
        this.inTransition = true;
        Server.getInstance().setCharacteridInTransition(this, cid);
    }

    public int getChannel() {
        return channel;
    }

    public Channel getChannelServer() {
        return Server.getInstance().getChannel(world, channel);
    }

    public World getWorldServer() {
        return Server.getInstance().getWorld(world);
    }

    public Channel getChannelServer(byte channel) {
        return Server.getInstance().getChannel(world, channel);
    }

    public boolean deleteCharacter(int cid, int senderAccId) {
        try {
            Character chr = Character.loadCharFromDB(cid, this, false);
            Integer partyid = chr.getWorldServer().getCharacterPartyid(cid);
            if (partyid != null) {
                this.setPlayer(chr);

                Party party = chr.getWorldServer().getParty(partyid);
                chr.setParty(party);
                chr.getMPC();
                chr.leaveParty();   // thanks Vcoc for pointing out deleted characters would still stay in a party

                this.setPlayer(null);
            }
            return Character.deleteCharFromDB(chr, senderAccId);
        } catch (SQLiteException ex) {
            log.error("deleteCharacter error", ex);
            return false;
        }
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String a) {
        this.accountName = a;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public int getWorld() {
        return world;
    }

    public void setWorld(int world) {
        this.world = world;
    }

    public void pongReceived() {
        lastPong = System.currentTimeMillis();
    }

    public void checkIfIdle(final IdleStateEvent event) {
        final long pingedAt = System.currentTimeMillis();
        sendPacket(PacketCreator.getPing());
        TimerManager.getInstance().schedule(() -> {
            try {
                if (lastPong < pingedAt) {
                    if (ioChannel.isActive()) {
                        log.info("Disconnected {} due to idling. Reason: {}", remoteAddress, event.state());
                        updateLoginState(Client.LOGIN_NOTLOGGEDIN);
                        disconnectSession();
                    }
                }
            } catch (NullPointerException e) {
                log.error("checkIfIdle error", e);
            }
        }, SECONDS.toMillis(15));
    }

    public Set<String> getMacs() {
        return Collections.unmodifiableSet(macs);
    }

    public int getGMLevel() {
        return gmlevel;
    }

    public void setGMLevel(int level) {
        gmlevel = level;
    }

    public void setScriptEngine(String name, ScriptEngine e) {
        engines.put(name, e);
    }

    public ScriptEngine getScriptEngine(String name) {
        return engines.get(name);
    }

    public void removeScriptEngine(String name) {
        engines.remove(name);
    }

    public NPCConversationManager getCM() {
        return NPCScriptManager.getInstance().getCM(this);
    }

    public QuestActionManager getQM() {
        return QuestScriptManager.getInstance().getQM(this);
    }

    public boolean acceptToS() {
        if (accountName == null) {
            return true;
        }

        boolean disconnect = false;
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.rawQuery("SELECT tos FROM accounts WHERE id = ?", new String[]{String.valueOf(accId)})) {
            int tosIdx = cursor.getColumnIndex("tos");
            if (tosIdx != -1) {
                if (cursor.moveToFirst()) {
                    int tosValue = cursor.getInt(tosIdx);
                    if (tosValue == 1) {
                        disconnect = true;
                    }
                }
            }

            ContentValues values = new ContentValues();
            values.put("tos", 1);
            con.update("accounts", values, "id = ?", new String[]{String.valueOf(accId)});
        } catch (SQLiteException e) {
            log.error("acceptToS error", e);
        }
        return disconnect;
    }

    public void checkChar(int accid) {  /// issue with multiple chars from same account login found by shavit, resinate
        if (!YamlConfig.config.server.USE_CHARACTER_ACCOUNT_CHECK) {
            return;
        }

        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (accid == chr.getAccountID()) {
                    log.warn("Chr {} has been removed from world {}. Possible Dupe attempt.", chr.getName(), GameConstants.WORLD_NAMES[w.getId()]);
                    chr.getClient().forceDisconnect();
                    w.getPlayerStorage().removePlayer(chr.getId());
                }
            }
        }
    }

    public int getVotePoints() {
        int points = 0;
        String[] columns = { "votepoints" };
        String selection = "id=?";
        String[] selectionArgs = { String.valueOf(accId) };
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.query("accounts", columns, selection, selectionArgs, null, null, null)) {
            if (cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex("votepoints");
                if (columnIndex != -1) {
                    points = cursor.getInt(columnIndex);
                }
            }
        } catch (SQLiteException e) {
            log.error("getVotePoints error", e);
        }
        votePoints = points;
        return votePoints;
    }

    public void addVotePoints(int points) {
        votePoints += points;
        saveVotePoints();
    }

    public void useVotePoints(int points) {
        if (points > votePoints) {
            //Should not happen, should probably log this
            return;
        }
        votePoints -= points;
        saveVotePoints();
        MapleLeafLogger.log(player, false, Integer.toString(points));
    }

    private void saveVotePoints() {
        ContentValues values = new ContentValues();
        values.put("votepoints", votePoints);
        String whereClause = "id=?";
        String[] whereArgs = { String.valueOf(accId) };
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            con.update("accounts", values, whereClause, whereArgs);
        } catch (SQLiteException e) {
            log.error("saveVotePoints error", e);
        }
    }

    public void lockClient() {
        lock.lock();
    }

    public void unlockClient() {
        lock.unlock();
    }

    public boolean tryacquireClient() {
        if (actionsSemaphore.tryAcquire()) {
            lockClient();
            return true;
        } else {
            return false;
        }
    }

    public void releaseClient() {
        unlockClient();
        actionsSemaphore.release();
    }

    public boolean tryacquireEncoder() {
        if (actionsSemaphore.tryAcquire()) {
            encoderLock.lock();
            return true;
        } else {
            return false;
        }
    }

    public void unlockEncoder() {
        encoderLock.unlock();
        actionsSemaphore.release();
    }

    private static class CharNameAndId {

        public String name;
        public int id;

        public CharNameAndId(String name, int id) {
            super();
            this.name = name;
            this.id = id;
        }
    }

    private static boolean checkHash(String hash, String type, String password) {
        try {
            MessageDigest digester = MessageDigest.getInstance(type);
            digester.update(password.getBytes(StandardCharsets.UTF_8), 0, password.length());
            return HexTool.toHexString(digester.digest()).replace(" ", "").toLowerCase().equals(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Encoding the string failed", e);
        }
    }

    public short getAvailableCharacterSlots() {
        return (short) Math.max(0, characterSlots - Server.getInstance().getAccountCharacterCount(accId));
    }

    public short getAvailableCharacterWorldSlots() {
        return (short) Math.max(0, characterSlots - Server.getInstance().getAccountWorldCharacterCount(accId, world));
    }

    public short getAvailableCharacterWorldSlots(int world) {
        return (short) Math.max(0, characterSlots - Server.getInstance().getAccountWorldCharacterCount(accId, world));
    }

    public short getCharacterSlots() {
        return characterSlots;
    }

    public void setCharacterSlots(byte slots) {
        characterSlots = slots;
    }

    public boolean canGainCharacterSlot() {
        return characterSlots < 15;
    }

    public synchronized boolean gainCharacterSlot() {
        if (canGainCharacterSlot()) {
            ContentValues values = new ContentValues();
            values.put("characterslots", characterSlots + 1);
            String whereClause = "id=?";
            String[] whereArgs = { String.valueOf(accId) };
            SQLiteDatabase con = DatabaseConnection.getConnection();
            try {
                con.update("accounts", values, whereClause, whereArgs);
            } catch (SQLiteException e) {
                log.error("gainCharacterSlot error", e);
            }
            return true;
        }
        return false;
    }

    public final byte getGReason() {
        String[] projection = { "greason" };
        String selection = "id = ?";
        String[] selectionArgs = { String.valueOf(accId) };
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.query("accounts", projection, selection, selectionArgs, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int greason = cursor.getInt(cursor.getColumnIndexOrThrow("greason"));
                return (byte) greason;
            }

        } catch (SQLiteException e) {
            log.error("getGReason error", e);
        }
        return 0;
    }

    public byte getGender() {
        return gender;
    }

    public void setGender(byte m) {
        this.gender = m;
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            ContentValues values = new ContentValues();
            values.put("gender", gender);
            con.update("accounts", values, "id = ?", new String[]{String.valueOf(accId)});
        } catch (SQLiteException e) {
            log.error("setGender error", e);
        }
    }

    private void announceDisableServerMessage() {
        if (!this.getWorldServer().registerDisabledServerMessage(player.getId())) {
            sendPacket(PacketCreator.serverMessage(""));
        }
    }

    public void announceServerMessage() {
        sendPacket(PacketCreator.serverMessage(this.getChannelServer().getServerMessage()));
    }

    public synchronized void announceBossHpBar(Monster mm, final int mobHash, Packet packet) {
        long timeNow = System.currentTimeMillis();
        int targetHash = player.getTargetHpBarHash();

        if (mobHash != targetHash) {
            if (timeNow - player.getTargetHpBarTime() >= SECONDS.toMillis(5)) {
                // is there a way to INTERRUPT this annoying thread running on the client that drops the boss bar after some time at every attack?
                announceDisableServerMessage();
                sendPacket(packet);

                player.setTargetHpBarHash(mobHash);
                player.setTargetHpBarTime(timeNow);
            }
        } else {
            announceDisableServerMessage();
            sendPacket(packet);

            player.setTargetHpBarTime(timeNow);
        }
    }

    public void sendPacket(Packet packet) {
        announcerLock.lock();
        try {
            ioChannel.writeAndFlush(packet);
        } finally {
            announcerLock.unlock();
        }
    }

    public void announceHint(String msg, int length) {
        sendPacket(PacketCreator.sendHint(msg, length, 10));
        sendPacket(PacketCreator.enableActions());
    }

    public void changeChannel(int channel) {
        Server server = Server.getInstance();
        if (player.isBanned()) {
            disconnect(false, false);
            return;
        }
        if (!player.isAlive() || FieldLimit.CANNOTMIGRATE.check(player.getMap().getFieldLimit())) {
            sendPacket(PacketCreator.enableActions());
            return;
        } else if (MiniDungeonInfo.isDungeonMap(player.getMapId())) {
            sendPacket(PacketCreator.serverNotice(5, "Changing channels or entering Cash Shop or MTS are disabled when inside a Mini-Dungeon."));
            sendPacket(PacketCreator.enableActions());
            return;
        }

        String[] socket = Server.getInstance().getInetSocket(this, getWorld(), channel);
        if (socket == null) {
            sendPacket(PacketCreator.serverNotice(1, "Channel " + channel + " is currently disabled. Try another channel."));
            sendPacket(PacketCreator.enableActions());
            return;
        }

        player.closePlayerInteractions();
        player.closePartySearchInteractions();

        player.unregisterChairBuff();
        server.getPlayerBuffStorage().addBuffsToStorage(player.getId(), player.getAllBuffs());
        server.getPlayerBuffStorage().addDiseasesToStorage(player.getId(), player.getAllDiseases());
        player.setDisconnectedFromChannelWorld();
        player.notifyMapTransferToPartner(-1);
        player.removeIncomingInvites();
        player.cancelAllBuffs(true);
        player.cancelAllDebuffs();
        player.cancelBuffExpireTask();
        player.cancelDiseaseExpireTask();
        player.cancelSkillCooldownTask();
        player.cancelQuestExpirationTask();
        //Cancelling magicdoor? Nope
        //Cancelling mounts? Noty

        player.getInventory(InventoryType.EQUIPPED).checked(false); //test
        player.getMap().removePlayer(player);
        player.clearBanishPlayerData();
        player.getClient().getChannelServer().removePlayer(player);

        player.saveCharToDB();

        player.setSessionTransitionState();
        try {
            sendPacket(PacketCreator.getChannelChange(InetAddress.getByName(socket[0]), Integer.parseInt(socket[1])));
        } catch (IOException e) {
            log.error("changeChannel error", e);
        }
    }

    public long getSessionId() {
        return this.sessionId;
    }

    public boolean canRequestCharlist() {
        return lastNpcClick + 877 < Server.getInstance().getCurrentTime();
    }

    public boolean canClickNPC() {
        return lastNpcClick + 500 < Server.getInstance().getCurrentTime();
    }

    public void setClickedNPC() {
        lastNpcClick = Server.getInstance().getCurrentTime();
    }

    public void removeClickedNPC() {
        lastNpcClick = 0;
    }

    public int getVisibleWorlds() {
        return visibleWorlds;
    }

    public void requestedServerlist(int worlds) {
        visibleWorlds = worlds;
        setClickedNPC();
    }

    public void closePlayerScriptInteractions() {
        this.removeClickedNPC();
        NPCScriptManager.getInstance().dispose(this);
        QuestScriptManager.getInstance().dispose(this);
    }

    public boolean attemptCsCoupon() {
        if (csattempt > 2) {
            resetCsCoupon();
            return false;
        }

        csattempt++;
        return true;
    }

    public void resetCsCoupon() {
        csattempt = 0;
    }

    public void enableCSActions() {
        sendPacket(PacketCreator.enableCSUse(player));
    }

    public boolean canBypassPin() {
        return LoginBypassCoordinator.getInstance().canLoginBypass(hwid, accId, false);
    }

    public boolean canBypassPic() {
        return LoginBypassCoordinator.getInstance().canLoginBypass(hwid, accId, true);
    }

    public int getLanguage() {
        return lang;
    }

    public void setLanguage(int lingua) {
        this.lang = lingua;
    }
}