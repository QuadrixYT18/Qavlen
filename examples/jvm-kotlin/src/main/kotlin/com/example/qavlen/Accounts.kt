package com.example.qavlen

import java.util.concurrent.ConcurrentHashMap

class Accounts(private val startingBalance: Long) {
    private val balances = ConcurrentHashMap<String, Long>()

    fun balance(id: String): Long = balances.getOrDefault(id, startingBalance)

    fun add(id: String, amount: Long): Long = balances.merge(id, startingBalance + amount) { current, _ -> current + amount }!!

    @Synchronized
    fun transfer(from: String, to: String, amount: Long): Boolean {
        if (balance(from) < amount) return false
        balances[from] = balance(from) - amount
        balances[to] = balance(to) + amount
        return true
    }

    fun reset(id: String) {
        balances.remove(id)
    }
}
