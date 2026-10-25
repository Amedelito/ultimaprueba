package es.mimic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.stream.Collectors;

public final class Mimic extends JavaPlugin implements Listener, TabExecutor {

    private static final String TAG = "mimic_display";
    private NamespacedKey pawKey, flagKey;
    private final Map<UUID, Active> act = new HashMap<>();

    /** Una pieza animada del modelo (cabeza, pata, cola...). */
    private static final class Part {
        ItemDisplay d; String rol; Vector3f pivot;
    }

    private static final class Active {
        Player p; String id; ConfigurationSection c;
        final List<Part> parts = new ArrayList<>();
        ItemStack paw; TextDisplay nick; Team oldTeam, ourTeam; Location last, lastTp;
        BukkitTask task; int t; double phase; float bodyYaw; boolean mov;
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        pawKey = new NamespacedKey(this, "paw");
        flagKey = new NamespacedKey(this, "disfrazado");
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) cleanLegacyPaw(p);
        var cmd = getCommand("transformar");
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        for (Team t : Bukkit.getScoreboardManager().getMainScoreboard().getTeams())
            if (t.getName().startsWith("mimic_")) t.unregister();
        // limpia displays huerfanos de un cierre brusco
        for (var w : Bukkit.getWorlds())
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class))
                if (e.getScoreboardTags().contains(TAG)) e.remove();
        for (var w : Bukkit.getWorlds())
            for (Entity e : w.getEntitiesByClass(TextDisplay.class))
                if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    @Override public void onDisable() {
        for (Active a : new ArrayList<>(act.values())) revert(a.p, true);
    }

    // ---------------------------------------------------------------- logica

    private ItemStack modelItem(Material mat, int cmd) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.setCustomModelData(cmd);
        item.setItemMeta(m);
        return item;
    }

    private ItemDisplay spawnDisplay(Player p, Location at, ItemStack item, boolean onlyOwner) {
        ItemDisplay d = p.getWorld().spawn(at, ItemDisplay.class, e -> {
            if (onlyOwner) e.setVisibleByDefault(false);
            e.setItemStack(item);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setInterpolationDuration(2);
            e.addScoreboardTag(TAG);
        });
        if (onlyOwner) p.showEntity(this, d);
        else if (getConfig().getBoolean("ocultar-a-si-mismo", false)) p.hideEntity(this, d);
        return d;
    }

    private boolean transform(Player p, String id) {
        ConfigurationSection c = getConfig().getConfigurationSection("criaturas." + id);
        if (c == null) return false;
        if (act.containsKey(p.getUniqueId())) revert(p, true);

        Active a = new Active();
        a.p = p; a.id = id; a.c = c;
        a.bodyYaw = p.getLocation().getYaw();

        Material mat = Material.matchMaterial(c.getString("material", "PAPER"));
        if (mat == null) mat = Material.PAPER;

        for (Map<?, ?> m : c.getMapList("partes")) {
            Part part = new Part();
            part.rol = String.valueOf(m.get("rol"));
            List<?> pv = (List<?>) m.get("pivote");
            part.pivot = new Vector3f(
                    (((Number) pv.get(0)).floatValue() - 8f) / 16f,
                    ((Number) pv.get(1)).floatValue() / 16f,
                    (((Number) pv.get(2)).floatValue() - 8f) / 16f);
            part.d = spawnDisplay(p, p.getLocation(), modelItem(mat, ((Number) m.get("modelo")).intValue()), false);
            a.parts.add(part);
        }

        int paw = c.getInt("modelo-mano", 0);
        if (paw > 0) {
            ItemStack pi = modelItem(mat, paw);
            ItemMeta pm = pi.getItemMeta();
            pm.setDisplayName(" ");
            pm.getPersistentDataContainer().set(pawKey, PersistentDataType.BYTE, (byte) 1);
            pi.setItemMeta(pm);
            a.paw = pi;
        }
        setupNick(a);

        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        setAttr(p, attr("SCALE", "GENERIC_SCALE"), c.getDouble("escala-jugador", 1.0));
        setAttr(p, attr("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"), 0.1 * c.getDouble("velocidad", 1.0));
        for (String s : c.getStringList("efectos")) {
            String[] sp = s.split(":");
            PotionEffectType t = effect(sp[0]);
            if (t == null) continue;
            int amp = sp.length > 1 ? Integer.parseInt(sp[1]) : 0;
            p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, amp, false, false, false));
        }

        p.getPersistentDataContainer().set(flagKey, PersistentDataType.BYTE, (byte) 1);
        act.put(p.getUniqueId(), a);
        ensurePaw(a);
        a.task = Bukkit.getScheduler().runTaskTimer(this, () -> tick(a), 0L, 1L);
        return true;
    }

    private void revert(Player p, boolean unused) {
        Active a = act.remove(p.getUniqueId());
        if (a != null) {
            if (a.task != null) a.task.cancel();
            for (Part part : a.parts) part.d.remove();
            if (a.nick != null) a.nick.remove();
            restoreTeam(a);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            for (String s : a.c.getStringList("efectos")) {
                PotionEffectType t = effect(s.split(":")[0]);
                if (t != null) p.removePotionEffect(t);
            }
        }
        setAttr(p, attr("SCALE", "GENERIC_SCALE"), 1.0);
        setAttr(p, attr("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"), 0.1);
        p.getPersistentDataContainer().remove(flagKey);
        cleanLegacyPaw(p);
    }

    private static float wrap(float a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    private void tick(Active a) {
        Player p = a.p;
        if (!p.isOnline() || p.isDead()) { revert(p, true); return; }
        ConfigurationSection c = a.c;
        Location l = p.getLocation();

        // ---- el cuerpo gira hacia donde caminas; la cabeza mira libre hasta 50 grados
        float py = l.getYaw(), diff = wrap(py - a.bodyYaw);
        if (a.mov) a.bodyYaw += Math.max(-25f, Math.min(25f, diff));
        else if (Math.abs(diff) > 50f) a.bodyYaw += diff - Math.signum(diff) * 50f;

        // ---- posicion: solo se teletransporta si algo cambio
        Location tl = l.clone();
        tl.setYaw(a.bodyYaw + (float) c.getDouble("giro", 0));
        tl.setPitch(0);
        if (a.lastTp == null || a.lastTp.getWorld() != tl.getWorld() || a.lastTp.getYaw() != tl.getYaw()
                || a.lastTp.distanceSquared(tl) > 1e-6) {
            for (Part part : a.parts) part.d.teleport(tl);
            a.lastTp = tl;
        }

        // ---- nick encima de la criatura
        if (a.nick != null) {
            Location nl = l.clone();
            nl.add(0, c.getDouble("nick-altura", 1.0) * (p.isSneaking() ? 0.93 : 1.0), 0);
            a.nick.teleport(nl);
        }

        // ---- patita en la mano principal cuando no llevas nada
        ensurePaw(a);

        if (++a.t % 2 != 0) return;

        // ---- animacion (cada 2 ticks, con interpolacion suave en el cliente)
        double dist = a.last != null && a.last.getWorld() == l.getWorld()
                ? Math.hypot(l.getX() - a.last.getX(), l.getZ() - a.last.getZ()) : 0;
        a.last = l;
        boolean mov = dist > 0.03;
        a.mov = mov;
        a.phase += mov ? Math.min(1.2, dist * 2.5 + 0.35) : 0.1;
        double s = Math.sin(a.phase);

        float k = (float) c.getDouble("escala", 1.0) * (p.isSneaking() ? 0.9f : 1f);
        boolean salta = c.getBoolean("salta", false);
        float amp = (float) Math.toRadians(c.getDouble("amplitud", 35));
        double bob = mov ? (salta ? Math.abs(Math.sin(a.phase * 0.5)) * 0.15 * k : Math.sin(a.phase * 2) * 0.03 * k) : 0;
        float roll = mov ? (float) (s * c.getDouble("balanceo", 0.06)) : 0f;

        Quaternionf g = new Quaternionf().rotateZ(roll);
        if (p.isSwimming() || p.isGliding()) g.rotateX((float) Math.toRadians(80));

        float headYaw = Math.max(-70f, Math.min(70f, wrap(py - a.bodyYaw)));
        float pitch = Math.max(-45f, Math.min(45f, l.getPitch()));
        boolean headFollows = getConfig().getBoolean("cabeza-sigue", true);

        for (Part part : a.parts) {
            Quaternionf rot = new Quaternionf();
            switch (part.rol) {
                case "pata_a" -> rot.rotateX(mov ? (float) (s * amp) : 0f);
                case "pata_b" -> rot.rotateX(mov ? (float) (-s * amp) : 0f);
                case "pata_s" -> rot.rotateX(mov ? (float) (Math.sin(a.phase * 0.5) * amp) : 0f);
                case "cola" -> rot.rotateY((float) (mov ? Math.sin(a.phase * 0.5) * 0.35 : Math.sin(a.t * 0.08) * 0.15));
                case "cabeza" -> {
                    if (headFollows) rot.rotateY((float) Math.toRadians(-headYaw)).rotateX((float) Math.toRadians(-pitch * 0.6));
                    else rot.rotateX((float) (mov ? Math.sin(a.phase * 2) * 0.04 : 0));
                }
                case "oreja_l", "oreja_r" -> rot.rotateX(mov ? (float) (0.45 + 0.1 * s) : 0.05f);
                case "aleta_l" -> rot.rotateZ((float) -(0.15 + (mov ? s * 0.5 : Math.sin(a.t * 0.1) * 0.05)));
                case "aleta_r" -> rot.rotateZ((float) (0.15 + (mov ? s * 0.5 : Math.sin(a.t * 0.1) * 0.05)));
                default -> { }
            }
            // rotar alrededor del pivote:  v' = k * G * (P - R*P + R*v) + bob
            Vector3f tr = new Vector3f(part.pivot);
            rot.transform(new Vector3f(part.pivot)).mul(-1f).add(part.pivot, tr); // tr = P - R*P
            g.transform(tr).mul(k).add(0, (float) bob, 0);
            Quaternionf q = new Quaternionf(g).mul(rot);
            part.d.setInterpolationDelay(0);
            part.d.setTransformation(new Transformation(tr, q, new Vector3f(k, k, k), new Quaternionf()));
        }

    }

    /** Busca el atributo por nombre: funciona en cualquier version 1.21.x (se renombraron en 1.21.3). */
    private Attribute attr(String... names) {
        for (String n : names) {
            try { return (Attribute) Attribute.class.getField(n).get(null); } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private void setAttr(Player p, Attribute at, double v) {
        if (at == null) return;
        AttributeInstance ai = p.getAttribute(at);
        if (ai != null) ai.setBaseValue(v);
    }

    private PotionEffectType effect(String n) {
        return Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(n.toLowerCase(Locale.ROOT)));
    }

    /** Si la mano principal esta vacia, pone la patita; la quita de los demas slots. */
    private void ensurePaw(Active a) {
        if (a.paw == null) return;
        PlayerInventory inv = a.p.getInventory();
        int held = inv.getHeldItemSlot();
        for (int i = 0; i < 9; i++)
            if (i != held && isPaw(inv.getItem(i))) inv.setItem(i, null);
        ItemStack cur = inv.getItem(held);
        if (cur == null || cur.getType().isAir()) inv.setItem(held, a.paw.clone());
    }

    /** Borra cualquier patita del inventario (al destransformar, morir o entrar). */
    private void cleanLegacyPaw(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] cs = inv.getContents();
        for (int i = 0; i < cs.length; i++) if (isPaw(cs[i])) inv.setItem(i, null);
        if (isPaw(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    private void setupNick(Active a) {
        Player p = a.p;
        TextColor color = NamedTextColor.WHITE;
        Scoreboard sb = p.getScoreboard();
        Team old = sb.getEntryTeam(p.getName());
        if (old != null) { try { color = old.color(); } catch (IllegalStateException ignored) { } }
        if (getConfig().getBoolean("nick.ocultar-vanilla", true)) {
            String tn = "mimic_" + p.getUniqueId().toString().substring(0, 8);
            Team t = sb.getTeam(tn);
            if (t == null) t = sb.registerNewTeam(tn);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            if (old != null) {
                try { TextColor oc = old.color(); if (oc != null) t.color(NamedTextColor.nearestTo(oc)); } catch (IllegalStateException ignored) { }
                t.prefix(old.prefix());
                t.suffix(old.suffix());
            }
            t.addEntry(p.getName());
            a.oldTeam = old;
            a.ourTeam = t;
        }
        if (getConfig().getBoolean("nick.mostrar", true)) {
            final TextColor col = color;
            a.nick = p.getWorld().spawn(p.getLocation(), TextDisplay.class, e -> {
                e.text(Component.text(p.getName(), col));
                e.setBillboard(Display.Billboard.CENTER);
                e.setPersistent(false);
                e.setTeleportDuration(1);
                e.addScoreboardTag(TAG);
            });
            p.hideEntity(this, a.nick);
        }
    }

    private void restoreTeam(Active a) {
        if (a.ourTeam == null) return;
        String name = a.p.getName();
        try { a.ourTeam.removeEntry(name); a.ourTeam.unregister(); } catch (IllegalStateException ignored) { }
        if (a.oldTeam != null) { try { a.oldTeam.addEntry(name); } catch (IllegalStateException ignored) { } }
    }

    private boolean isPaw(ItemStack i) {
        return i != null && i.hasItemMeta()
                && i.getItemMeta().getPersistentDataContainer().has(pawKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- eventos

    @EventHandler public void onQuit(PlayerQuitEvent e) { revert(e.getPlayer(), true); }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!act.containsKey(p.getUniqueId())
                && p.getPersistentDataContainer().has(flagKey, PersistentDataType.BYTE)) {
            revert(p, false); // limpieza tras cierre brusco
        }
        cleanLegacyPaw(p);
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!act.containsKey(p.getUniqueId())) return;
        e.getDrops().removeIf(this::isPaw);
        revert(p, false);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (isPaw(e.getCurrentItem()) || isPaw(e.getCursor())) { e.setCancelled(true); return; }
        if (e.getClick() == ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player pl
                && isPaw(pl.getInventory().getItem(e.getHotbarButton()))) e.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        if (isPaw(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isPaw(e.getMainHandItem()) || isPaw(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler public void onHeld(PlayerItemHeldEvent e) {
        Active a = act.get(e.getPlayer().getUniqueId());
        if (a == null || a.paw == null) return;
        PlayerInventory inv = e.getPlayer().getInventory();
        if (isPaw(inv.getItem(e.getPreviousSlot()))) inv.setItem(e.getPreviousSlot(), null);
        ItemStack n = inv.getItem(e.getNewSlot());
        if (n == null || n.getType().isAir()) inv.setItem(e.getNewSlot(), a.paw.clone());
    }

    // ---------------------------------------------------------------- comandos

    private List<Player> targets(CommandSender s, String arg) {
        List<Player> r = new ArrayList<>();
        String a = arg.toLowerCase(Locale.ROOT);
        if (a.equals("yo") || a.equals("me")) { if (s instanceof Player p) r.add(p); }
        else if (a.equals("all") || a.equals("todos")) r.addAll(Bukkit.getOnlinePlayers());
        else if (a.startsWith("@")) {
            try { for (Entity e : Bukkit.selectEntities(s, arg)) if (e instanceof Player p) r.add(p); }
            catch (IllegalArgumentException ex) { /* selector invalido */ }
        } else { Player p = Bukkit.getPlayerExact(arg); if (p != null) r.add(p); }
        return r;
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("mimic.use")) { s.sendMessage("§cSolo OPs."); return true; }
        if (a.length == 0) {
            s.sendMessage("§e/transformar <yo|all|jugador|@selector> <criatura>");
            s.sendMessage("§e/transformar quitar <yo|all|jugador>  §7| §e/transformar lista §7| §e/transformar recargar");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("lista")) {
            s.sendMessage("§aCriaturas: §f" + String.join(", ", criaturas()));
            return true;
        }
        if (sub.equals("recargar")) { reloadConfig(); s.sendMessage("§aConfig recargada."); return true; }
        if (sub.equals("quitar") || sub.equals("normal")) {
            List<Player> t = targets(s, a.length > 1 ? a[1] : "yo");
            if (t.isEmpty()) { s.sendMessage("§cObjetivo no encontrado."); return true; }
            t.forEach(p -> revert(p, true));
            s.sendMessage("§aVuelven a la normalidad: §f" + t.size());
            return true;
        }
        if (a.length < 2) { s.sendMessage("§cFalta la criatura. Usa /transformar lista"); return true; }
        String id = a[1].toLowerCase(Locale.ROOT);
        if (!criaturas().contains(id)) { s.sendMessage("§cCriatura desconocida. Usa /transformar lista"); return true; }
        List<Player> t = targets(s, a[0]);
        if (t.isEmpty()) { s.sendMessage("§cObjetivo no encontrado."); return true; }
        t.forEach(p -> transform(p, id));
        s.sendMessage("§aTransformados en §f" + id + "§a: §f" + t.size());
        return true;
    }

    private Set<String> criaturas() {
        ConfigurationSection cs = getConfig().getConfigurationSection("criaturas");
        return cs == null ? Set.of() : cs.getKeys(false);
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("mimic.use")) return List.of();
        List<String> out = new ArrayList<>();
        List<String> who = new ArrayList<>(List.of("yo", "all"));
        Bukkit.getOnlinePlayers().forEach(p -> who.add(p.getName()));
        if (a.length == 1) { out.addAll(who); out.addAll(List.of("quitar", "lista", "recargar")); }
        else if (a.length == 2) {
            if (a[0].equalsIgnoreCase("quitar")) out.addAll(who);
            else out.addAll(criaturas());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }
}
