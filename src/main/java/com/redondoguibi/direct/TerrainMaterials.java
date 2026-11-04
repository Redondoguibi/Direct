package com.redondoguibi.direct;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.registries.BuiltInRegistries;
final class TerrainMaterials {
    static final int GENERIC=0,STONE=1,WOOD=2,FOLIAGE=3,GLASS=4,WATER=5,EMISSIVE=6,METAL=7,PLANT=8,PLANT_UPPER=9;
    private record Rect(float u0,float v0,float u1,float v1) {
        boolean contains(float u,float v){return u>u0&&u<u1&&v>v0&&v<v1;}
    }
    private static volatile java.util.List<Rect> waterSprites=java.util.List.of();
    static void refresh(net.minecraft.client.renderer.texture.TextureAtlas atlas){
        var rects=new java.util.ArrayList<Rect>();
        for(String name:java.util.List.of("water_still","water_flow","water_overlay")){
            var id=net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/"+name);
            var sprite=atlas.getSprite(id);
            if(sprite.contents().name().equals(id))rects.add(new Rect(sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1()));
        }
        waterSprites=java.util.List.copyOf(rects);
    }
    static boolean waterSprite(float u,float v){for(var rect:waterSprites)if(rect.contains(u,v))return true;return false;}
    static int classify(BlockState state){
        var id=BuiltInRegistries.BLOCK.getKey(state.getBlock());String name=id.getPath();
        if(state.is(net.minecraft.world.level.block.Blocks.WATER))return WATER;
        if(state.is(BlockTags.LEAVES))return FOLIAGE;
        if(state.getBlock() instanceof net.minecraft.world.level.block.BushBlock){
            var half=net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
            return state.hasProperty(half)&&state.getValue(half)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER?PLANT_UPPER:PLANT;
        }
        if(state.is(BlockTags.LOGS)||state.is(BlockTags.PLANKS))return WOOD;
        if(name.contains("glass")||name.contains("ice"))return GLASS;
        if(state.getLightEmission()>0)return EMISSIVE;
        if(state.is(BlockTags.BASE_STONE_OVERWORLD)||state.is(BlockTags.BASE_STONE_NETHER)||name.contains("stone"))return STONE;
        if(name.contains("iron")||name.contains("copper")||name.contains("gold")||name.contains("metal"))return METAL;
        return GENERIC;
    }
    private TerrainMaterials(){}
}
