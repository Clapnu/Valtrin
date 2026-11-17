package com.mojang.brigadier.suggestion;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.concurrent.CompletableFuture;
public interface SuggestionProvider<S>{ CompletableFuture<Suggestions> getSuggestions(CommandContext<S> c, SuggestionsBuilder b) throws CommandSyntaxException; }
