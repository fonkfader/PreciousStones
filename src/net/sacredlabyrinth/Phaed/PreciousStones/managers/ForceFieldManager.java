package net.sacredlabyrinth.Phaed.PreciousStones.managers;

import net.sacredlabyrinth.Phaed.PreciousStones.*;
import net.sacredlabyrinth.Phaed.PreciousStones.vectors.ChunkVec;
import net.sacredlabyrinth.Phaed.PreciousStones.vectors.Field;
import net.sacredlabyrinth.Phaed.PreciousStones.vectors.Vec;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Handles fields
 *
 * @author Phaed
 */
public final class ForceFieldManager
{
    private final HashMap<FieldFlag, HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>>> fieldLists = new HashMap<FieldFlag, HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>>>();
    private final HashMap<ChunkVec, HashMap<FieldFlag, List<Field>>> sourceFields = new HashMap<ChunkVec, HashMap<FieldFlag, List<Field>>>();

    private Queue<Field> deletionQueue = new LinkedList<Field>();
    private PreciousStones plugin;

    /**
     *
     */
    public ForceFieldManager()
    {
        plugin = PreciousStones.getInstance();
    }

    /**
     * Clear out the fields in memory
     */
    public void clearChunkLists()
    {
        fieldLists.clear();
        sourceFields.clear();
    }

    public void drawSourceFields()
    {
        for (ChunkVec cv : sourceFields.keySet())
        {
            PreciousStones.getLog().info(cv.toString());

            HashMap<FieldFlag, List<Field>> flagList = sourceFields.get(cv);

            for (FieldFlag flag : flagList.keySet())
            {
                PreciousStones.getLog().info("   -" + flag);

                List<Field> fields = flagList.get(flag);

                for (Field field : fields)
                {
                    PreciousStones.getLog().info("     -" + Helper.toLocationString(field.getLocation()));
                }
            }
        }
    }

    /**
     * Add a brand new field
     *
     * @param fieldBlock
     * @param owner
     * @param event
     * @return confirmation
     */
    public Field add(Block fieldBlock, Player player, BlockPlaceEvent event)
    {
        boolean notify = true;

        if (plugin.getPlayerManager().getPlayerData(player.getName()).isDisabled())
        {
            return null;
        }

        FieldSettings fs = plugin.getSettingsManager().getFieldSettings(Helper.toRawTypeId(fieldBlock));

        if (fs == null)
        {
            return null;
        }

        // deny if world is blacklisted

        if (plugin.getSettingsManager().isBlacklistedWorld(fieldBlock.getWorld()))
        {
            return null;
        }

        // check if in worldguard region

        if (!plugin.getWorldGuardManager().canBuildField(player, fieldBlock, fs))
        {
            ChatBlock.sendMessage(player, "The field intersects with a WorldGuard region you are not allowed in.");
            event.setCancelled(true);
            return null;
        }

        // check if the pstone limit has been reached by the player

        if (plugin.getLimitManager().reachedLimit(player, fs))
        {
            event.setCancelled(true);
            return null;
        }

        // purchase pstone

        if (!plugin.getPermissionsManager().has(player, "preciousstones.bypass.purchase"))
        {
            if (fs.getPrice() > 0 && !purchase(player, fs.getPrice()))
            {
                return null;
            }
        }

        String owner = fs.hasDefaultFlag(FieldFlag.NO_OWNER) ? "Server" : player.getName();
        boolean isChild = false;
        boolean isImport = false;
        Field field;

        // create field

        if (plugin.getCuboidManager().hasOpenCuboid(player))
        {
            CuboidEntry ce = plugin.getCuboidManager().getOpenCuboid(player);

            if ((ce.getField().getSettings().getMixingGroup() != fs.getMixingGroup() || fs.getMixingGroup() == 0) && ce.getField().getSettings().getRawTypeId() != fs.getRawTypeId())
            {
                plugin.getCuboidManager().cancelOpenCuboid(player);
                ChatBlock.sendMessage(player, "Cannot mix fields that are not in the same mixing group.");
                event.setCancelled(true);
                return null;
            }

            if (fs.getPrice() > ce.getField().getSettings().getPrice())
            {
                plugin.getCuboidManager().cancelOpenCuboid(player);
                ChatBlock.sendMessage(player, "Cannot add on properties of more valuable fields");
                event.setCancelled(true);
                return null;
            }

            field = new Field(fieldBlock, 0, 0, owner);

            // set up parent/child relationship

            ce.getField().addChild(field);
            field.setParent(ce.getField());
            isChild = true;
            notify = false;

            // import field flags

            if (ce.getField().getRawTypeId() != fs.getRawTypeId())
            {
                ce.getField().importFlags(fs.getDefaultFlags());
                ChatBlock.sendMessage(player, ChatColor.YELLOW + Helper.capitalize(fs.getTitle()) + "'s field flags imported");
                isImport = true;
            }

        }
        else
        {
            field = new Field(fieldBlock, fs.getRadius(), fs.getHeight(), owner);
        }

        field.setSettings(fs);

        String clan = plugin.getSimpleClansManager().getClan(player.getName());

        if (clan != null)
        {
            field.addAllowed("c:" + clan);
        }

        // add to database (skip foresters and activate them)

        if (fs.hasDefaultFlag(FieldFlag.FORESTER) || fs.hasDefaultFlag(FieldFlag.FORESTER_SHRUBS))
        {
            plugin.getForesterManager().add(field, player.getName());
        }
        else
        {
            // add count to player data

            PlayerData data = plugin.getPlayerManager().getPlayerData(player.getName());
            data.incrementFieldCount(field.getSettings().getRawTypeId());

            // open cuboid definition

            if (field.hasFlag(FieldFlag.CUBOID) && !isImport)
            {
                if (isChild)
                {
                    plugin.getCuboidManager().openChild(player, field);
                }
            }

            // insert the field into database

            plugin.getStorageManager().insertField(field);
        }

        // add to collection

        addToCollection(field);

        // visualize the field

        if (field.hasFlag(FieldFlag.VISUALIZE_ON_PLACE) && !isChild)
        {
            plugin.getVisualizationManager().visualizeSingleFieldFast(player, field);
        }

        if (notify)
        {
            if (plugin.getForceFieldManager().isBreakable(field.getBlock()))
            {
                plugin.getCommunicationManager().notifyPlaceBreakableFF(player, field.getBlock());
            }
            else
            {
                plugin.getCommunicationManager().notifyPlaceFF(player, field.getBlock());
            }
        }

        return field;
    }

    /**
     * Add the field to the collection, used by add()
     *
     * @param field
     */
    public void addToCollection(Field field)
    {
        String world = field.getWorld();

        ChunkVec cv = field.toChunkVec();

        List<FieldFlag> flags = field.getSettings().getDefaultFlags();

        for (FieldFlag flag : flags)
        {
            // add to fields collection

            HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(flag);

            if (wLists == null)
            {
                wLists = new HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>>();
            }

            HashMap<ChunkVec, HashMap<Vec, Field>> w = wLists.get(world);

            if (w == null)
            {
                w = new HashMap<ChunkVec, HashMap<Vec, Field>>();
            }

            HashMap<Vec, Field> c = w.get(cv);

            if (c == null)
            {
                c = new HashMap<Vec, Field>();
            }

            c.put(field.toVec(), field);
            w.put(cv, c);
            wLists.put(world, w);
            fieldLists.put(flag, wLists);
        }

        // add to sources collection

        addSourceField(field);
    }

    /**
     * Add a fields envoleped chunks to the source fields collection
     *
     * @param field
     */
    public void addSourceField(Field field)
    {
        if (field.isDisabled())
        {
            return;
        }

        Set<ChunkVec> scvs = field.getEnvelopingChunks();

        for (ChunkVec scv : scvs)
        {
            HashMap<FieldFlag, List<Field>> sf = sourceFields.get(scv);

            if (sf == null)
            {
                sf = new HashMap<FieldFlag, List<Field>>();
            }

            List<FieldFlag> flags = field.getSettings().getDefaultFlags();

            for (FieldFlag flag : flags)
            {
                List<Field> fields = sf.get(flag);

                if (fields == null)
                {
                    fields = new LinkedList<Field>();
                }

                if (!fields.contains(field))
                {
                    fields.add(field);
                }

                sf.put(flag, fields);
            }

            sourceFields.put(scv, sf);
        }
    }

    /**
     * Deletes a field from memory and from the database
     *
     * @param field the field to delete
     */
    public void deleteField(final Field field)
    {
        // remove from fields collection

        if (field == null)
        {
            return;
        }

        if (field.getSettings() != null)
        {
            List<FieldFlag> flags = field.getSettings().getDefaultFlags();

            for (FieldFlag flag : flags)
            {
                HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(flag);

                if (wLists == null)
                {
                    return;
                }

                HashMap<ChunkVec, HashMap<Vec, Field>> w = wLists.get(field.getWorld());

                if (w != null)
                {
                    HashMap<Vec, Field> c = w.get(field.toChunkVec());

                    if (c != null)
                    {
                        c.remove(field.toVec());
                    }
                }
            }
        }

        // remove from sources collection

        removeSourceField(field);

        FieldSettings fs = field.getSettings();

        // remove from forester

        if (fs.hasDefaultFlag(FieldFlag.FORESTER) || fs.hasDefaultFlag(FieldFlag.FORESTER_SHRUBS))
        {
            plugin.getForesterManager().remove(field);
        }

        // delete any snitch entries

        if (fs.hasDefaultFlag(FieldFlag.SNITCH))
        {
            plugin.getStorageManager().deleteSnitchEntries(field);
        }

        // remove from grief-undo and delete any records on the database

        if (fs.hasDefaultFlag(FieldFlag.GRIEF_REVERT))
        {
            plugin.getGriefUndoManager().remove(field);
            plugin.getStorageManager().deleteBlockGrief(field);
        }

        // remove the count from the owner

        PlayerData data = plugin.getPlayerManager().getPlayerData(field.getOwner());
        data.decrementFieldCount(field.getSettings().getRawTypeId());

        // delete siblings and parent if exists

        if (field.isParent())
        {
            for (Field c : field.getChildren())
            {
                c.clearParent();
                queueRelease(c);
            }

            field.clearChildren();
        }

        // if the childs parent is not open, then remove the whole family

        if (field.isChild())
        {
            if (!field.getParent().isOpen())
            {
                queueRelease(field.getParent());
            }
        }

        flushDrop();

        // delete from database

        field.markForDeletion();
        plugin.getStorageManager().offerField(field);
    }

    /**
     * Remove a field's enveloped chunks from the source fields collection
     *
     * @param field
     */
    public void removeSourceField(Field field)
    {
        Set<ChunkVec> scvs = field.getEnvelopingChunks();

        for (ChunkVec scv : scvs)
        {
            HashMap<FieldFlag, List<Field>> sf = sourceFields.get(scv);

            if (sf != null)
            {
                List<FieldFlag> flags = field.getSettings().getDefaultFlags();

                for (FieldFlag flag : flags)
                {
                    List<Field> fields = sf.get(flag);

                    if (fields != null)
                    {
                        fields.remove(field);

                        if (fields.isEmpty())
                        {
                            sf.remove(flag);
                        }
                    }
                }

                if (sf.isEmpty())
                {
                    sourceFields.remove(scv);
                }
            }
        }
    }

    /**
     * Get all fields a player/g:group/c:clan/* is allowed in for a world
     *
     * @param target
     * @param world
     * @return
     */
    public List<Field> getFields(String target, World world)
    {
        List<Field> out = new LinkedList<Field>();

        HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(FieldFlag.ALL);

        if (wLists != null)
        {
            HashMap<ChunkVec, HashMap<Vec, Field>> w = wLists.get(world.getName());

            if (w != null)
            {
                for (ChunkVec cv : w.keySet())
                {
                    HashMap<Vec, Field> c = w.get(cv);

                    for (Field field : c.values())
                    {
                        if (target.equals("*"))
                        {
                            out.add(field);
                            continue;
                        }

                        if (target.contains("g:"))
                        {
                            String group = target.substring(2);

                            if (plugin.getPermissionsManager().inGroup(field.getOwner(), world, group))
                            {
                                out.add(field);
                            }
                            continue;
                        }

                        if (target.contains("c:"))
                        {
                            String clan = target.substring(2);

                            if (plugin.getSimpleClansManager().isInClan(field.getOwner(), clan))
                            {
                                out.add(field);
                            }
                            continue;
                        }

                        if (field.isOwner(target))
                        {
                            out.add(field);
                        }
                    }
                }
            }
        }

        return out;
    }

    /**
     * Get all fields on a world, optionally based on field flags
     *
     * @param worldName
     * @return all fields from the database that match the world
     */
    public HashMap<ChunkVec, HashMap<Vec, Field>> getFields(String worldName, FieldFlag flag)
    {
        HashMap<ChunkVec, HashMap<Vec, Field>> out = new HashMap<ChunkVec, HashMap<Vec, Field>>();

        HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(flag);

        if (wLists == null)
        {
            return null;
        }

        HashMap<ChunkVec, HashMap<Vec, Field>> w = wLists.get(worldName);

        if (w == null)
        {
            return null;
        }

        out.putAll(w);
        return out;
    }

    /**
     * Gets the field object from a block, if the block is a field
     *
     * @param block the block that is a field
     * @return the field object from the block
     */
    public Field getField(Block block)
    {
        HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(FieldFlag.ALL);

        if (wLists == null)
        {
            return null;
        }

        HashMap<ChunkVec, HashMap<Vec, Field>> w = wLists.get(block.getLocation().getWorld().getName());

        if (w != null)
        {
            HashMap<Vec, Field> c = w.get(new ChunkVec(block));

            if (c != null)
            {
                Field field = c.get(new Vec(block));

                if (field != null)
                {
                    if (field.getRawTypeId() != Helper.toRawTypeId(block))
                    {
                        deleteField(field);
                        return null;
                    }
                }

                return field;
            }
        }
        return null;
    }

    /**
     * Looks for the block in our field collection
     *
     * @param fieldBlock
     * @return confirmation
     */
    public boolean isField(Block fieldBlock)
    {
        return getField(fieldBlock) != null;
    }

    /**
     * Total number of forcefield stones
     *
     * @return the count
     */
    public int getCount()
    {
        int size = 0;

        HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(FieldFlag.ALL);

        if (wLists == null)
        {
            return 0;
        }

        for (HashMap<ChunkVec, HashMap<Vec, Field>> w : wLists.values())
        {
            if (w != null)
            {
                for (HashMap<Vec, Field> c : w.values())
                {
                    size += c.size();
                }
            }
        }
        return size;
    }

    /**
     * Gets field counts for player/g:group/c:clan/*
     *
     * @param target
     * @return
     */
    public HashMap<Integer, Integer> getFieldCounts(String target)
    {
        HashMap<Integer, Integer> counts = new HashMap<Integer, Integer>();
        List<World> worlds = plugin.getServer().getWorlds();

        for (World world : worlds)
        {
            List<Field> fields = getFields(target, world);

            for (Field field : fields)
            {
                if (counts.containsKey(field.getRawTypeId()))
                {
                    counts.put(field.getRawTypeId(), counts.get(field.getRawTypeId()) + 1);
                }
                else
                {
                    counts.put(field.getRawTypeId(), 1);
                }
            }
        }

        return counts;
    }

    /**
     * Clean up orphan fields
     *
     * @param world
     * @return
     */
    public int cleanOrphans(World world)
    {
        int cleanedCount = 0;
        boolean currentChunkLoaded = false;
        ChunkVec currentChunk = null;

        HashMap<ChunkVec, HashMap<Vec, Field>> w = getFields(world.getName(), FieldFlag.ALL);

        if (w != null)
        {
            for (HashMap<Vec, Field> fields : w.values())
            {
                if (fields != null)
                {
                    for (Field field : fields.values())
                    {
                        // ensure chunk is loaded prior to polling

                        ChunkVec cv = field.toChunkVec();

                        if (!cv.equals(currentChunk))
                        {
                            if (!currentChunkLoaded)
                            {
                                if (currentChunk != null)
                                {
                                    world.unloadChunk(currentChunk.getX(), currentChunk.getZ());
                                }
                            }

                            currentChunkLoaded = world.isChunkLoaded(cv.getX(), cv.getZ());

                            if (!currentChunkLoaded)
                            {
                                world.loadChunk(cv.getX(), cv.getZ());
                            }

                            currentChunk = cv;
                        }

                        int type = world.getBlockTypeIdAt(field.getX(), field.getY(), field.getZ());

                        if (type != field.getTypeId())
                        {
                            cleanedCount++;
                            queueRelease(field);
                        }
                    }
                }
            }
        }
        flush();

        if (cleanedCount != 0)
        {
            PreciousStones.log("({0}) orphan-fields: {1}", world.getName(), cleanedCount);
        }
        return cleanedCount;
    }

    /**
     * Revert orphan fields
     *
     * @param world
     * @return
     */
    public int revertOrphans(World world)
    {
        int revertedCount = 0;
        boolean currentChunkLoaded = false;
        ChunkVec currentChunk = null;

        HashMap<ChunkVec, HashMap<Vec, Field>> w = getFields(world.getName(), FieldFlag.ALL);

        if (w != null)
        {
            for (HashMap<Vec, Field> fields : w.values())
            {
                for (Field field : fields.values())
                {
                    // ensure chunk is loaded prior to polling

                    ChunkVec cv = field.toChunkVec();

                    if (!cv.equals(currentChunk))
                    {
                        if (!currentChunkLoaded)
                        {
                            if (currentChunk != null)
                            {
                                world.unloadChunk(currentChunk.getX(), currentChunk.getZ());
                            }
                        }

                        currentChunkLoaded = world.isChunkLoaded(cv.getX(), cv.getZ());

                        if (!currentChunkLoaded)
                        {
                            world.loadChunk(cv.getX(), cv.getZ());
                        }

                        currentChunk = cv;
                    }

                    int type = world.getBlockTypeIdAt(field.getX(), field.getY(), field.getZ());

                    if (!plugin.getSettingsManager().isFieldType(type))
                    {
                        revertedCount++;
                        Block block = world.getBlockAt(field.getX(), field.getY(), field.getZ());
                        block.setTypeId(field.getTypeId());
                        if (field.getData() > 0)
                        {
                            block.setData(field.getData());
                        }
                    }
                }
            }
        }

        return revertedCount;
    }

    /**
     * Whether the block is an unbreakable field
     *
     * @param block
     * @return confirmation
     */
    public boolean isBreakable(Block block)
    {
        FieldSettings fs = plugin.getSettingsManager().getFieldSettings(Helper.toRawTypeId(block));

        return fs != null && fs.hasDefaultFlag(FieldFlag.BREAKABLE);
    }

    /**
     * Returns the source block for the field
     *
     * @param field
     * @return the source block
     */
    public Block getBlock(Field field)
    {
        World world = plugin.getServer().getWorld(field.getWorld());

        if (world == null)
        {
            return null;
        }

        return world.getBlockAt(field.getX(), field.getY(), field.getZ());
    }

    /**
     * Whether a Redstone hooked field is in a disabled state
     *
     * @param field
     * @return confirmation
     */
    public boolean isRedstoneHookedDisabled(Field field)
    {
        Block block = plugin.getForceFieldManager().getBlock(field);

        if (isAnywayPowered(block))
        {
            return false;
        }

        Material topmat = block.getRelative(BlockFace.UP).getType();

        if (topmat.equals(Material.STONE_PLATE) || topmat.equals(Material.WOOD_PLATE))
        {
            return true;
        }

        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};

        for (BlockFace face : faces)
        {
            Block faceblock = block.getRelative(face);

            if (faceblock.getType().equals(Material.REDSTONE_TORCH_OFF))
            {
                return true;
            }

            if (faceblock.getType().equals(Material.STONE_BUTTON))
            {
                return true;
            }

            if (faceblock.getType().equals(Material.LEVER) && faceblock.getBlockPower() == 0)
            {
                return true;
            }

            if (faceblock.getType().equals(Material.REDSTONE_WIRE) && faceblock.getBlockPower() == 0)
            {
                return true;
            }
        }

        BlockFace[] upfaces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

        Block upblock = block.getRelative(BlockFace.UP);

        for (BlockFace face : upfaces)
        {
            Block faceblock = upblock.getRelative(face);

            if (faceblock.getType().equals(Material.REDSTONE_WIRE) && faceblock.getBlockPower() == 0)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether there is current any where around the block
     *
     * @param block
     * @return confirmation
     */
    public boolean isAnywayPowered(Block block)
    {
        if (block.isBlockIndirectlyPowered() || block.isBlockPowered())
        {
            return true;
        }

        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};

        for (BlockFace face : faces)
        {
            Block source = block.getRelative(face);

            if (source.getType().equals(Material.REDSTONE_WIRE))
            {
                if (source.getBlockPower() > 0)
                {
                    return true;
                }
            }
        }

        BlockFace[] upfaces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

        Block upblock = block.getRelative(BlockFace.UP);

        for (BlockFace face : upfaces)
        {
            Block faceblock = upblock.getRelative(face);

            if (faceblock.getType().equals(Material.REDSTONE_WIRE))
            {
                if (faceblock.getBlockPower() > 0)
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Send a message to all allowed players of the field
     *
     * @param field
     * @param msg
     */
    public void announceAllowedPlayers(Field field, String msg)
    {
        List<String> allowed = field.getAllAllowed();

        for (String playerName : allowed)
        {
            Player pl = Helper.matchSinglePlayer(playerName);

            if (pl != null)
            {
                ChatBlock.sendMessage(pl, ChatColor.DARK_GRAY + " * " + ChatColor.AQUA + msg);
            }
        }
    }

    /**
     * Whether the block is touching a field block
     *
     * @param block
     * @return the touching block, null if none
     */
    public Block touchingFieldBlock(Block block)
    {
        if (block == null)
        {
            return null;
        }

        for (int x = -1; x <= 1; x++)
        {
            for (int z = -1; z <= 1; z++)
            {
                for (int y = -1; y <= 1; y++)
                {
                    if (x == 0 && y == 0 && z == 0)
                    {
                        continue;
                    }

                    Block surroundingblock = block.getWorld().getBlockAt(block.getX() + x, block.getY() + y, block.getZ() + z);

                    if (plugin.getForceFieldManager().isField(surroundingblock))
                    {
                        return surroundingblock;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Clean up snitch lists of the field
     *
     * @param player
     * @param field
     * @return confirmation
     */
    public boolean cleanSnitchList(Field field)
    {
        FieldSettings fs = field.getSettings();

        if (fs.hasDefaultFlag(FieldFlag.SNITCH))
        {
            field.clearSnitch();
            plugin.getStorageManager().deleteSnitchEntries(field);
            return true;
        }

        return false;
    }

    /**
     * Sets the name of the field and all intersecting fields
     *
     * @param player
     * @param field
     * @param name
     * @return count of fields set
     */
    public boolean setNameFields(Field field, String name)
    {
        FieldSettings fs = field.getSettings();

        if ((fs.hasNameableFlag()) && !field.getName().equals(name))
        {
            field.setName(name);
            plugin.getStorageManager().offerField(field);
            return true;
        }

        return false;
    }

    /**
     * Returns a list of players who are inside the field
     *
     * @param player
     * @param field
     * @return list of player names
     */
    public HashSet<String> getWho(Field field)
    {
        return plugin.getEntryManager().getInhabitants(field);
    }

    /**
     * Whether the player is allowed in the field
     *
     * @param field
     * @param playerName
     * @return
     */
    public boolean isAllowed(Field field, String playerName)
    {
        Player player = Helper.matchSinglePlayer(playerName);

        if (player != null)
        {
            if (plugin.getPermissionsManager().has(player, "preciousstones.admin.allowed"))
            {
                return true;
            }
        }

        return field.isAllowed(playerName);
    }

    /**
     * Determine whether a player is allowed on a field
     *
     * @param fieldBlock
     * @param playerName
     * @return confirmation
     */
    public boolean isAllowed(Block fieldBlock, String playerName)
    {
        Field field = getField(fieldBlock);

        return field != null && isAllowed(field, playerName);

    }

    /**
     * Get allowed players on a field
     *
     * @param player
     * @param field
     * @param overlapped
     * @return allowed entry object per user
     */
    public List<String> getAllowed(Player player, Field field)
    {
        return field.getAllAllowed();
    }

    /**
     * Add allowed player to a field
     *
     * @param player
     * @param field
     * @param allowedName
     * @return count of fields the player was allowed in
     */
    public boolean addAllowed(Field field, String allowedName)
    {
        if (!field.isAllowed(allowedName))
        {
            field.addAllowed(allowedName);
            plugin.getStorageManager().offerField(field);
            return true;
        }

        return false;
    }

    /**
     * Add allowed player to all your force fields
     *
     * @param player
     * @param allowedName
     * @return count of fields allowed
     */
    public int allowAll(Player player, String allowedName)
    {
        List<Field> fields = getOwnersFields(player, FieldFlag.ALL);

        int allowedCount = 0;

        for (Field field : fields)
        {
            if (!field.isAllowed(allowedName))
            {
                field.addAllowed(allowedName);
                allowedCount++;
            }
            plugin.getStorageManager().offerField(field);
        }

        return allowedCount;
    }

    /**
     * Remove allowed player from a field
     *
     * @param player
     * @param allowedName
     * @param field
     * @return count of fields the player was removed from
     */
    public boolean removeAllowed(Field field, String allowedName)
    {
        if (field.isAllowed(allowedName))
        {
            field.removeAllowed(allowedName);

            plugin.getStorageManager().offerField(field);
            return true;
        }
        return false;
    }

    /**
     * Whether another field that is overlapping the field is owned by the allowedName
     *
     * @param field
     * @param allowedName
     * @return
     */
    public boolean conflictOfInterestExists(Field field, String allowedName)
    {
        Set<Field> sources = field.getOverlappingFields();

        for (Field source : sources)
        {
            if (source.hasFlag(FieldFlag.NO_CONFLICT))
            {
                continue;
            }

            if (field.getOwner().equalsIgnoreCase(source.getOwner()))
            {
                continue;
            }

            if (source.isOwner(allowedName))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * If the field has any sub-plotted fields
     *
     * @param field
     */
    public boolean hasSubFields(Field field)
    {
        Set<Field> sources = field.getOverlappingFields();

        for (Field source : sources)
        {
            if (source.getSettings().isAllowedOnlyField(field))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Remove allowed player to all your force fields
     *
     * @param player
     * @param allowedName
     * @return count of fields the player was removed from
     */
    public int removeAll(Player player, String allowedName)
    {
        List<Field> fields = getOwnersFields(player, FieldFlag.ALL);

        int removedCount = 0;

        for (Field field : fields)
        {
            if (conflictOfInterestExists(field, allowedName))
            {
                ChatBlock.sendMessage(player, ChatColor.RED + Helper.capitalize(allowedName) + " was not disallowed, one of the fields is overlapping one of yours " + field);
                continue;
            }

            if (field.isAllowed(allowedName))
            {
                field.removeAllowed(allowedName);
                removedCount++;
            }
            plugin.getStorageManager().offerField(field);
        }

        return removedCount;
    }

    /**
     * Determine whether a player is the owner of the field
     *
     * @param fieldBlock
     * @param playerName
     * @return confirmation
     */
    public boolean isOwner(Block fieldBlock, String playerName)
    {
        Field field = getField(fieldBlock);

        return field != null && field.isOwner(playerName);
    }

    /**
     * Return the owner of a field
     *
     * @param fieldBlock a block which is a field
     * @return owner's name
     */
    public String getOwner(Block fieldBlock)
    {
        Field field = getField(fieldBlock);

        if (field != null)
        {
            return field.getOwner();
        }
        return "";
    }

    /**
     * Get all the fields belonging to players, optionally you can pass field flags and it will only retrieve those matching the field flags
     *
     * @param player
     * @return the fields
     */
    public List<Field> getOwnersFields(Player player, FieldFlag flag)
    {
        List<Field> out = new LinkedList<Field>();

        HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(flag);

        if (wLists == null)
        {
            return null;
        }

        for (HashMap<ChunkVec, HashMap<Vec, Field>> w : wLists.values())
        {
            if (w != null)
            {
                for (HashMap<Vec, Field> fields : w.values())
                {
                    for (Field field : fields.values())
                    {
                        if (field.isOwner(player.getName()))
                        {
                            out.add(field);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Get all fields matching this flag that are touching this chunk
     *
     * @param cv
     * @return
     */
    public List<Field> getSourceFields(ChunkVec cv, FieldFlag flag)
    {
        HashMap<FieldFlag, List<Field>> flagList = sourceFields.get(cv);

        if (flagList != null)
        {
            List<Field> fields = flagList.get(flag);

            if (fields != null)
            {
                return new LinkedList<Field>(fields);
            }
        }

        return new LinkedList<Field>();
    }

    /**
     * Returns a field in the location that matches the field flag(s)
     *
     * @param loc
     * @param flags
     * @return the fields
     */
    public Field getSourceField(Location loc, FieldFlag flag)
    {
        List<Field> sources = getSourceFields(loc.getBlock().getChunk(), flag);

        List<Field> out = new LinkedList<Field>();

        for (Field field : sources)
        {
            if (field.envelops(loc))
            {
                out.add(field);
            }
        }

        return immediateField(out);
    }

    /**
     * Gets the smallest field from a list of fields
     *
     * @param fields
     * @return
     */
    public Field immediateField(List<Field> fields)
    {
        if (fields == null || fields.isEmpty())
        {
            return null;
        }

        // sort fields by volume

        Collections.sort(fields, new Comparator<Field>()
        {
            public int compare(Field f1, Field f2)
            {
                Integer o1 = f1.getVolume();
                Integer o2 = f2.getVolume();

                return o2.compareTo(o1);
            }
        });

        // return smallest fields where a player can fit

        for (Field smallest : fields)
        {
            if (smallest.getVolume() > 1 && smallest.getComputedHeight() > 1)
            {
                return smallest;
            }
        }

        return fields.get(0);
    }

    /**
     * If a field in the location that matches the field flag(s)
     *
     * @param loc
     * @param flags
     * @return result
     */
    public boolean hasSourceField(Location loc, FieldFlag flag)
    {
        return getSourceField(loc, flag) != null;
    }

    /**
     * Returns the fields that the location is in match the field flag(s)
     *
     * @param loc
     * @param flags
     * @return the fields
     */
    public List<Field> getSourceFields(Location loc, FieldFlag flag)
    {
        List<Field> sources = getSourceFields(new ChunkVec(loc.getBlock().getChunk()), flag);

        for (Iterator it = sources.iterator(); it.hasNext(); )
        {
            Field field = (Field) it.next();

            if (!field.envelops(loc))
            {
                it.remove();
            }
        }

        return sources;
    }

    /**
     * Get all fields matching this flag that are touching this chunk
     *
     * @param cv
     * @return
     */
    public List<Field> getSourceFields(Chunk chunk, FieldFlag flag)
    {
        return getSourceFields(new ChunkVec(chunk), flag);
    }

    /**
     * Returns the first field found in the location and that the player is not allowed in, optionally with field flags
     *
     * @param loc
     * @param playerName
     * @return the fields
     */
    public Field getNotAllowedSourceField(Location loc, String playerName, FieldFlag flag)
    {
        List<Field> sources = getSourceFields(loc.getBlock().getChunk(), flag);

        List<Field> out = new LinkedList<Field>();

        for (Iterator it = sources.iterator(); it.hasNext(); )
        {
            Field field = (Field) it.next();

            if (field.envelops(loc) && !isAllowed(field, playerName) && !plugin.getSimpleClansManager().inWar(field, playerName))
            {
                out.add(field);
            }
        }

        return immediateField(out);
    }

    /**
     * Returns the first  conflict field found in the location and that the player is not allowed in, optionally with field flags
     *
     * @param loc
     * @param playerName
     * @return the fields
     */
    public Field getNotAllowedConflictSourceField(Location loc, String playerName, FieldFlag flag)
    {
        List<Field> sources = getSourceFields(loc.getBlock().getChunk(), flag);

        List<Field> out = new LinkedList<Field>();

        for (Iterator it = sources.iterator(); it.hasNext(); )
        {
            Field field = (Field) it.next();

            if (field.envelops(loc) && !field.hasFlag(FieldFlag.NO_CONFLICT) && !isAllowed(field, playerName) && !plugin.getSimpleClansManager().inWar(field, playerName))
            {
                out.add(field);
            }
        }

        return immediateField(out);
    }

    /**
     * Returns the fields that the location is in and that the player is allowed in, optionally with field flags
     *
     * @param loc
     * @param playerName
     * @return the fields
     */
    public List<Field> getAllowedSourceFields(Location loc, String playerName, FieldFlag flag)
    {
        List<Field> sources = getSourceFields(loc.getBlock().getChunk(), flag);

        for (Iterator it = sources.iterator(); it.hasNext(); )
        {
            Field field = (Field) it.next();

            if (!field.envelops(loc) || (!isAllowed(field, playerName) && !plugin.getSimpleClansManager().inWar(field, playerName)))
            {
                it.remove();
            }
        }

        return sources;
    }

    /**
     * Returns the fields that the location is in and that the player is not allowed in, optionally with field flags
     *
     * @param loc
     * @param playerName
     * @return the fields
     */
    public List<Field> getNotAllowedSourceFields(Location loc, String playerName, FieldFlag flag)
    {
        List<Field> sources = getSourceFields(loc.getBlock().getChunk(), flag);

        for (Iterator it = sources.iterator(); it.hasNext(); )
        {
            Field field = (Field) it.next();

            if (!field.envelops(loc) || isAllowed(field, playerName) || plugin.getSimpleClansManager().inWar(field, playerName))
            {
                it.remove();
            }
        }

        return sources;
    }

    /**
     * Returns the fields in the chunk and adjacent chunks
     *
     * @param loc
     * @param chunkradius
     * @return the fields
     */
    public Set<Field> getFieldsInCustomArea(Location loc, int chunkradius, FieldFlag flag)
    {
        Set<Field> out = new HashSet<Field>();

        int xlow = (loc.getBlockX() >> 4) - chunkradius;
        int xhigh = (loc.getBlockX() >> 4) + chunkradius;
        int zlow = (loc.getBlockZ() >> 4) - chunkradius;
        int zhigh = (loc.getBlockZ() >> 4) + chunkradius;

        for (int x = xlow; x <= xhigh; x++)
        {
            for (int z = zlow; z <= zhigh; z++)
            {
                List<Field> fields = getSourceFields(new ChunkVec(x, z, loc.getWorld().getName()), flag);

                if (fields != null)
                {
                    out.addAll(fields);
                }
            }
        }

        return out;
    }

    /**
     * Returns the fields in the chunk and adjacent chunks that the player is allowe din
     *
     * @param loc
     * @param chunkradius
     * @return the fields
     */
    public Set<Field> getFieldsInCustomArea(Location loc, int chunkradius, FieldFlag flag, Player player)
    {
        Set<Field> out = new HashSet<Field>();

        int xlow = (loc.getBlockX() >> 4) - chunkradius;
        int xhigh = (loc.getBlockX() >> 4) + chunkradius;
        int zlow = (loc.getBlockZ() >> 4) - chunkradius;
        int zhigh = (loc.getBlockZ() >> 4) + chunkradius;

        for (int x = xlow; x <= xhigh; x++)
        {
            for (int z = zlow; z <= zhigh; z++)
            {
                List<Field> fields = getSourceFields(new ChunkVec(x, z, loc.getWorld().getName()), flag);

                if (fields != null)
                {
                    for (Field field : fields)
                    {
                        if (field.isAllowed(player.getName()))
                        {
                            out.add(field);
                        }
                    }
                }
            }
        }

        return out;
    }

    /**
     * Returns the field pointed at
     *
     * @param player
     * @return the field
     */
    public Field getPointedField(Player player)
    {
        if (player.getLocation().getY() < 127)
        {
            Block targetBlock = player.getTargetBlock(plugin.getSettingsManager().getThroughFieldsSet(), 128);

            if (targetBlock != null)
            {
                Field f = getField(targetBlock);

                if (f != null)
                {
                    if (f.isChild())
                    {
                        f = f.getParent();
                    }

                    if (isAllowed(f, player.getName()))
                    {
                        return f;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Returns the field if he's standing in at least one allowed field, optionally matching field flags
     *
     * @param blockInArea
     * @param player
     * @return the field
     */
    public Field getOneAllowedField(Block blockInArea, Player player, FieldFlag flag)
    {
        Field pointed = getPointedField(player);

        if (pointed != null)
        {
            return pointed;
        }

        List<Field> sources = getAllowedSourceFields(blockInArea.getLocation(), player.getName(), flag);

        return immediateField(sources);
    }

    /**
     * Whether the location is in a use protected area that the player is not allowed in
     *
     * @param loc
     * @param player
     * @param type_id
     * @return the field, null if its not
     */
    public boolean isUseProtected(Location loc, Player player, int type_id)
    {
        return findUseProtected(loc, player, type_id) != null;
    }

    /**
     * Find a use protected area in the location that the player is not allowed in
     *
     * @param loc
     * @param player
     * @param type_id
     * @return the field, null if its not
     */
    public Field findUseProtected(Location loc, Player player, int type_id)
    {
        List<Field> sources = getNotAllowedSourceFields(loc, player.getName(), FieldFlag.ALL);

        LinkedList<Field> out = new LinkedList<Field>();

        for (Field field : sources)
        {
            FieldSettings fs = field.getSettings();

            if (!fs.canUse(type_id))
            {
                out.add(field);
            }
        }

        return immediateField(out);
    }


    /**
     * Return the first field that conflicts with the unbreakable
     *
     * @param placedBlock
     * @param placer
     * @return the field, null if none found
     */
    public Field unbreakableConflicts(Block placedBlock, Player placer)
    {
        List<Field> sources = getSourceFields(placedBlock.getLocation(), FieldFlag.ALL);

        LinkedList<Field> out = new LinkedList<Field>();

        for (Field field : sources)
        {
            if (field.hasFlag(FieldFlag.NO_CONFLICT))
            {
                continue;
            }

            if (isAllowed(field, placer.getName()))
            {
                continue;
            }

            if (field.envelops(placedBlock))
            {
                out.add(field);
            }
        }

        return immediateField(out);
    }

    /**
     * Return the first field that conflicts with the field block
     *
     * @param placedBlock
     * @param placer
     * @return the field, null if none found
     */
    public Field fieldConflicts(Block placedBlock, Player placer)
    {
        FieldSettings fs = plugin.getSettingsManager().getFieldSettings(Helper.toRawTypeId(placedBlock));

        if (fs == null)
        {
            return null;
        }

        if (fs.hasDefaultFlag(FieldFlag.NO_CONFLICT))
        {
            return null;
        }

        // create throwaway field to test intersection

        Field placedField = new Field(placedBlock, fs.getRadius(), fs.getHeight());

        Set<Field> overlapping = placedField.getOverlappingFields();

        LinkedList<Field> out = new LinkedList<Field>();

        for (Field field : overlapping)
        {
            if (field.hasFlag(FieldFlag.NO_CONFLICT))
            {
                continue;
            }

            if (isAllowed(field, placer.getName()))
            {
                continue;
            }

            if (field.intersects(placedField))
            {
                out.add(field);
            }
        }

        return immediateField(out);
    }

    /**
     * Return the first field that conflicts with the cuboid entry
     *
     * @param placedBlock
     * @param placer
     * @return the field, null if none found
     */
    public Field fieldConflicts(CuboidEntry ce, Player placer)
    {
        if (ce.getField().hasFlag(FieldFlag.NO_CONFLICT))
        {
            return null;
        }

        // create throwaway field to test intersection

        Set<Field> overlapping = ce.getField().getOverlappingFields();

        LinkedList<Field> out = new LinkedList<Field>();

        for (Field field : overlapping)
        {
            if (field.hasFlag(FieldFlag.NO_CONFLICT))
            {
                continue;
            }

            if (isAllowed(field, placer.getName()))
            {
                continue;
            }

            if (field.intersects(ce.getField()))
            {
                out.add(field);
            }
        }

        return immediateField(out);
    }

    /**
     * Allows all owners of fields that are overlapping
     *
     * @param field
     */
    public void addAllowOverlappingOwners(Field field)
    {
        FieldSettings fs = field.getSettings();

        if (fs == null)
        {
            return;
        }

        if (fs.hasDefaultFlag(FieldFlag.NO_CONFLICT))
        {
            return;
        }

        // create throwaway field to test intersection

        Set<Field> overlapping = field.getOverlappingFields();

        for (Field overlap : overlapping)
        {
            if (overlap.hasFlag(FieldFlag.NO_CONFLICT))
            {
                continue;
            }

            if (overlap.isAllowed(field.getOwner()))
            {
                field.addAllowed(overlap.getOwner());
            }
        }
    }

    /**
     * Get all touching fields
     *
     * @param scopedBlock
     * @param materialInHand
     * @return the fields
     */
    public HashSet<Field> getTouchingFields(Block scopedBlock, Material materialInHand)
    {
        HashSet<Field> out = new HashSet<Field>();

        FieldSettings fs = plugin.getSettingsManager().getFieldSettings(materialInHand.getId());

        if (fs == null)
        {
            return out;
        }

        // create throwaway field to test intersection

        Field placedField = new Field(scopedBlock, fs.getRadius(), fs.getHeight());

        Set<Field> overlapping = placedField.getOverlappingFields();

        for (Field field : overlapping)
        {
            FieldSettings fsArea = plugin.getSettingsManager().getFieldSettings(field.getRawTypeId());

            if (fsArea == null)
            {
                plugin.getForceFieldManager().queueRelease(field);
                continue;
            }

            if (field.hasFlag(FieldFlag.NO_CONFLICT))
            {
                continue;
            }

            if (field.intersects(placedField))
            {
                out.add(field);
            }
        }

        return out;
    }

    /**
     * Deletes all fields belonging to a player
     *
     * @param playerName the players
     * @return the count of deleted fields
     */
    public int deleteBelonging(String playerName)
    {
        int deletedFields = 0;

        HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(FieldFlag.ALL);

        if (wLists == null)
        {
            return 0;
        }

        for (HashMap<ChunkVec, HashMap<Vec, Field>> w : wLists.values())
        {
            if (w != null)
            {
                for (HashMap<Vec, Field> fields : w.values())
                {
                    for (Field field : fields.values())
                    {
                        if (field.getOwner().equalsIgnoreCase(playerName))
                        {
                            queueRelease(field);
                            deletedFields++;
                        }
                    }
                }
            }
        }

        flush();

        return deletedFields;
    }

    /**
     * Deletes a field from the collection
     *
     * @param block
     */
    public void release(Block block)
    {
        Field field = getField(block);

        deleteField(field);

        if (plugin.getSettingsManager().isDropOnDelete())
        {
            dropBlock(block);
        }
    }

    /**
     * Deletes a field from the collection and drops it
     *
     * @param block
     */
    public void releaseAndDrop(Field field)
    {
        deleteField(field);
        dropBlock(field.getBlock());
    }

    /**
     * Deletes a field silently (no drop)
     *
     * @param field
     */
    public void silentRelease(Field field)
    {
        deleteField(field);
    }

    /**
     * Adds a field to deletion queue
     *
     * @param fieldBlock
     */
    public void queueRelease(Block fieldBlock)
    {
        Field field = getField(fieldBlock);

        if (!deletionQueue.contains(field))
        {
            deletionQueue.add(field);
        }
    }

    /**
     * Adds a field to deletion queue
     *
     * @param field
     */
    public void queueRelease(Field field)
    {
        if (!deletionQueue.contains(field))
        {
            deletionQueue.add(field);
        }
    }

    /**
     * Delete fields in deletion queue
     */
    public void flush()
    {
        while (deletionQueue.size() > 0)
        {
            Field pending = deletionQueue.poll();

            deleteField(pending);

            if (plugin.getSettingsManager().isDropOnDelete())
            {
                dropBlock(pending);
            }
        }
    }

    /**
     * Delete fields in deletion queue, force a drop
     */
    public void flushDrop()
    {
        while (deletionQueue.size() > 0)
        {
            Field pending = deletionQueue.poll();

            deleteField(pending);
            dropBlock(pending);
        }
    }

    /**
     * Delete fields the the player is standing on
     *
     * @param player
     * @param field
     * @return count of fields deleted
     */
    public int deleteFields(List<Field> sourceFields)
    {
        int deletedCount = 0;

        for (Field f : sourceFields)
        {
            queueRelease(f);
            deletedCount++;
        }

        if (deletedCount > 0)
        {
            flush();
        }
        return deletedCount;
    }

    /**
     * Delete fields of a certain type
     *
     * @param typeId
     * @return count of fields deleted
     */
    public int deleteFieldsOfType(int typeId)
    {
        int deletedCount = 0;

        HashMap<String, HashMap<ChunkVec, HashMap<Vec, Field>>> wLists = fieldLists.get(FieldFlag.ALL);

        if (wLists != null)
        {
            for (HashMap<ChunkVec, HashMap<Vec, Field>> w : wLists.values())
            {
                if (w != null)
                {
                    for (ChunkVec cv : w.keySet())
                    {
                        HashMap<Vec, Field> c = w.get(cv);

                        for (Field field : c.values())
                        {
                            if (field.getRawTypeId() == typeId)
                            {
                                queueRelease(field);
                                deletedCount++;
                            }
                        }
                    }
                }
            }
        }

        if (deletedCount > 0)
        {
            flush();
        }
        return deletedCount;
    }

    /**
     * Drops a block
     *
     * @param field
     */
    public void dropBlock(Field field)
    {
        World world = plugin.getServer().getWorld(field.getWorld());
        Block block = world.getBlockAt(field.getX(), field.getY(), field.getZ());

        if (plugin.getSettingsManager().isFieldType(block))
        {
            ItemStack is = new ItemStack(block.getTypeId(), 1, (short) 0, block.getData());
            block.setType(Material.AIR);
            world.dropItemNaturally(block.getLocation(), is);
        }
    }

    /**
     * Drops a block
     *
     * @param block
     */
    public void dropBlock(Block block)
    {
        World world = block.getWorld();
        ItemStack is = new ItemStack(block.getTypeId(), 1, (short) 0, block.getData());

        if (plugin.getSettingsManager().isFieldType(block))
        {
            block.setType(Material.AIR);
            world.dropItemNaturally(block.getLocation(), is);
        }
    }

    /**
     * Removes money from player's account
     *
     * @param player
     * @param amount
     * @return
     */
    public boolean purchase(Player player, double amount)
    {
        if (plugin.getVaultManager().hasEconomy())
        {
            if (plugin.getVaultManager().hasMoney(player, amount))
            {
                plugin.getVaultManager().playerCharge(player, amount);
            }
            else
            {
                player.sendMessage(ChatColor.RED + "You do not have sufficient money in your account");
                return false;
            }
        }

        return true;
    }

    /**
     * Credits money back to player's account
     *
     * @param player
     * @param amount
     */
    public void refund(Player player, double amount)
    {
        if (plugin.getVaultManager().hasEconomy())
        {
            plugin.getVaultManager().playerCredit(player, amount);
            player.sendMessage(ChatColor.AQUA + "Your account has been credited");
        }
    }
}
