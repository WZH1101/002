<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Vue购物商城</title>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
    <script src="https://cdn.jsdelivr.net/npm/vue@2.6.14/dist/vue.js"></script>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
        }
        
        body {
            background-color: #f8f9fa;
            color: #333;
            line-height: 1.6;
        }
        
        .container {
            max-width: 1200px;
            margin: 0 auto;
            padding: 0 15px;
        }
        
        header {
            background: linear-gradient(135deg, #6a11cb 0%, #2575fc 100%);
            color: white;
            padding: 20px 0;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
        }
        
        .header-content {
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        
        .logo {
            font-size: 28px;
            font-weight: 700;
            display: flex;
            align-items: center;
        }
        
        .logo i {
            margin-right: 10px;
        }
        
        .cart-icon {
            position: relative;
            cursor: pointer;
            font-size: 24px;
        }
        
        .cart-count {
            position: absolute;
            top: -8px;
            right: -8px;
            background-color: #ff4757;
            color: white;
            border-radius: 50%;
            width: 20px;
            height: 20px;
            font-size: 12px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: bold;
        }
        
        .main-content {
            display: flex;
            margin-top: 30px;
            gap: 30px;
        }
        
        .products-section {
            flex: 3;
        }
        
        .cart-section {
            flex: 1;
            background-color: white;
            border-radius: 10px;
            padding: 20px;
            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.05);
            height: fit-content;
            position: sticky;
            top: 20px;
        }
        
        .section-title {
            font-size: 22px;
            margin-bottom: 20px;
            padding-bottom: 10px;
            border-bottom: 2px solid #eee;
            color: #2d3436;
        }
        
        .filters {
            display: flex;
            margin-bottom: 20px;
            gap: 15px;
            flex-wrap: wrap;
        }
        
        .filter-btn {
            padding: 8px 16px;
            background-color: white;
            border: 1px solid #ddd;
            border-radius: 20px;
            cursor: pointer;
            transition: all 0.3s ease;
        }
        
        .filter-btn:hover, .filter-btn.active {
            background-color: #2575fc;
            color: white;
            border-color: #2575fc;
        }
        
        .products-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
            gap: 25px;
        }
        
        .product-card {
            background-color: white;
            border-radius: 10px;
            overflow: hidden;
            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.05);
            transition: transform 0.3s ease, box-shadow 0.3s ease;
            position: relative;
        }
        
        .product-card:hover {
            transform: translateY(-5px);
            box-shadow: 0 10px 20px rgba(0, 0, 0, 0.1);
        }
        
        .product-image {
            height: 180px;
            background-color: #f8f9fa;
            display: flex;
            align-items: center;
            justify-content: center;
            overflow: hidden;
        }
        
        .product-image img {
            max-width: 100%;
            max-height: 100%;
            object-fit: cover;
            transition: transform 0.5s ease;
        }
        
        .product-card:hover .product-image img {
            transform: scale(1.05);
        }
        
        .product-info {
            padding: 20px;
        }
        
        .product-title {
            font-size: 18px;
            font-weight: 600;
            margin-bottom: 10px;
            color: #2d3436;
        }
        
        .product-category {
            font-size: 14px;
            color: #6c757d;
            margin-bottom: 10px;
        }
        
        .product-price {
            font-size: 22px;
            font-weight: 700;
            color: #2575fc;
            margin-bottom: 15px;
        }
        
        .add-to-cart-btn {
            width: 100%;
            padding: 10px;
            background-color: #2575fc;
            color: white;
            border: none;
            border-radius: 5px;
            cursor: pointer;
            font-weight: 600;
            transition: background-color 0.3s ease;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        
        .add-to-cart-btn i {
            margin-right: 8px;
        }
        
        .add-to-cart-btn:hover {
            background-color: #1a68e8;
        }
        
        .add-to-cart-btn.added {
            background-color: #2ed573;
        }
        
        .cart-items {
            margin-bottom: 20px;
            max-height: 400px;
            overflow-y: auto;
        }
        
        .cart-item {
            display: flex;
            align-items: center;
            padding: 15px 0;
            border-bottom: 1px solid #eee;
        }
        
        .cart-item-image {
            width: 60px;
            height: 60px;
            background-color: #f8f9fa;
            border-radius: 8px;
            display: flex;
            align-items: center;
            justify-content: center;
            margin-right: 15px;
        }
        
        .cart-item-image img {
            max-width: 90%;
            max-height: 90%;
        }
        
        .cart-item-details {
            flex: 1;
        }
        
        .cart-item-title {
            font-weight: 600;
            margin-bottom: 5px;
        }
        
        .cart-item-price {
            color: #2575fc;
            font-weight: 600;
        }
        
        .cart-item-controls {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        
        .quantity-btn {
            width: 25px;
            height: 25px;
            border-radius: 50%;
            background-color: #f1f2f6;
            border: none;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        
        .quantity-btn:hover {
            background-color: #dfe4ea;
        }
        
        .cart-item-quantity {
            font-weight: 600;
        }
        
        .remove-item {
            color: #ff4757;
            cursor: pointer;
            font-size: 18px;
            margin-left: 10px;
        }
        
        .cart-summary {
            border-top: 1px solid #eee;
            padding-top: 20px;
        }
        
        .summary-row {
            display: flex;
            justify-content: space-between;
            margin-bottom: 10px;
        }
        
        .total {
            font-size: 20px;
            font-weight: 700;
            color: #2d3436;
            margin-top: 10px;
            padding-top: 10px;
            border-top: 1px solid #eee;
        }
        
        .checkout-btn {
            width: 100%;
            padding: 15px;
            background: linear-gradient(135deg, #6a11cb 0%, #2575fc 100%);
            color: white;
            border: none;
            border-radius: 8px;
            font-size: 18px;
            font-weight: 600;
            cursor: pointer;
            margin-top: 20px;
            transition: transform 0.2s ease;
        }
        
        .checkout-btn:hover {
            transform: translateY(-2px);
        }
        
        .empty-cart {
            text-align: center;
            color: #6c757d;
            padding: 30px 0;
        }
        
        .empty-cart i {
            font-size: 50px;
            margin-bottom: 15px;
            color: #dfe4ea;
        }
        
        footer {
            text-align: center;
            padding: 30px 0;
            margin-top: 50px;
            color: #6c757d;
            border-top: 1px solid #eee;
        }
        
        @media (max-width: 992px) {
            .main-content {
                flex-direction: column;
            }
            
            .cart-section {
                position: static;
            }
        }
        
        @media (max-width: 576px) {
            .header-content {
                flex-direction: column;
                gap: 15px;
            }
            
            .products-grid {
                grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
            }
            
            .filters {
                justify-content: center;
            }
        }
        
        .notification {
            position: fixed;
            bottom: 20px;
            right: 20px;
            background-color: #2ed573;
            color: white;
            padding: 15px 25px;
            border-radius: 8px;
            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.1);
            display: flex;
            align-items: center;
            z-index: 1000;
            transform: translateY(100px);
            opacity: 0;
            transition: transform 0.3s ease, opacity 0.3s ease;
        }
        
        .notification.show {
            transform: translateY(0);
            opacity: 1;
        }
        
        .notification i {
            margin-right: 10px;
            font-size: 20px;
        }
        
        .sale-badge {
            position: absolute;
            top: 10px;
            left: 10px;
            background-color: #ff4757;
            color: white;
            padding: 5px 10px;
            border-radius: 4px;
            font-size: 12px;
            font-weight: 600;
        }
        
        .discount-price {
            color: #ff4757;
            font-weight: 700;
        }
        
        .original-price {
            text-decoration: line-through;
            color: #999;
            font-size: 16px;
            margin-left: 5px;
        }
    </style>
</head>
<body>
    <div id="app">
        <header>
            <div class="container">
                <div class="header-content">
                    <div class="logo">
                        <i class="fas fa-shopping-bag"></i>
                        Vue购物商城
                    </div>
                    <div class="cart-icon" @click="toggleCart">
                        <i class="fas fa-shopping-cart"></i>
                        <div class="cart-count">{{ cartItemCount }}</div>
                    </div>
                </div>
            </div>
        </header>
        
        <div class="container">
            <div class="main-content">
                <div class="products-section">
                    <h2 class="section-title">精选商品</h2>
                    
                    <div class="filters">
                        <button 
                            class="filter-btn" 
                            :class="{active: activeFilter === 'all'}"
                            @click="activeFilter = 'all'"
                        >
                            全部商品
                        </button>
                        <button 
                            class="filter-btn" 
                            :class="{active: activeFilter === 'electronics'}"
                            @click="activeFilter = 'electronics'"
                        >
                            电子产品
                        </button>
                        <button 
                            class="filter-btn" 
                            :class="{active: activeFilter === 'clothing'}"
                            @click="activeFilter = 'clothing'}"
                        >
                            服装鞋帽
                        </button>
                        <button 
                            class="filter-btn" 
                            :class="{active: activeFilter === 'books'}"
                            @click="activeFilter = 'books'"
                        >
                            图书文娱
                        </button>
                        <button 
                            class="filter-btn" 
                            :class="{active: activeFilter === 'sale'}"
                            @click="activeFilter = 'sale'"
                        >
                            特价商品
                        </button>
                    </div>
                    
                    <div class="products-grid">
                        <div 
                            class="product-card" 
                            v-for="product in filteredProducts" 
                            :key="product.id"
                        >
                            <div class="product-image">
                                <img :src="product.image" :alt="product.name">
                                <div class="sale-badge" v-if="product.onSale">特价</div>
                            </div>
                            <div class="product-info">
                                <h3 class="product-title">{{ product.name }}</h3>
                                <div class="product-category">{{ product.category }}</div>
                                <div class="product-price">
                                    <span v-if="product.onSale" class="discount-price">¥{{ product.discountPrice }}</span>
                                    <span :class="{'original-price': product.onSale}">¥{{ product.price }}</span>
                                </div>
                                <button 
                                    class="add-to-cart-btn" 
                                    :class="{added: isInCart(product.id)}"
                                    @click="addToCart(product)"
                                >
                                    <i class="fas" :class="isInCart(product.id) ? 'fa-check' : 'fa-cart-plus'"></i>
                                    {{ isInCart(product.id) ? '已添加' : '加入购物车' }}
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
                
                <div class="cart-section" :style="{display: showCart ? 'block' : 'none'}">
                    <h2 class="section-title">购物车</h2>
                    
                    <div class="cart-items" v-if="cart.length > 0">
                        <div class="cart-item" v-for="item in cart" :key="item.id">
                            <div class="cart-item-image">
                                <img :src="item.image" :alt="item.name">
                            </div>
                            <div class="cart-item-details">
                                <div class="cart-item-title">{{ item.name }}</div>
                                <div class="cart-item-price">¥{{ item.onSale ? item.discountPrice : item.price }}</div>
                            </div>
                            <div class="cart-item-controls">
                                <button class="quantity-btn" @click="decreaseQuantity(item)">
                                    <i class="fas fa-minus"></i>
                                </button>
                                <span class="cart-item-quantity">{{ item.quantity }}</span>
                                <button class="quantity-btn" @click="increaseQuantity(item)">
                                    <i class="fas fa-plus"></i>
                                </button>
                                <div class="remove-item" @click="removeFromCart(item)">
                                    <i class="fas fa-trash"></i>
                                </div>
                            </div>
                        </div>
                    </div>
                    
                    <div class="empty-cart" v-else>
                        <i class="fas fa-shopping-cart"></i>
                        <p>购物车是空的</p>
                    </div>
                    
                    <div class="cart-summary" v-if="cart.length > 0">
                        <div class="summary-row">
                            <span>商品件数:</span>
                            <span>{{ cartItemCount }}</span>
                        </div>
                        <div class="summary-row">
                            <span>商品总额:</span>
                            <span>¥{{ subtotal }}</span>
                        </div>
                        <div class="summary-row">
                            <span>节省金额:</span>
                            <span>¥{{ totalSavings }}</span>
                        </div>
                        <div class="summary-row total">
                            <span>应付总额:</span>
                            <span>¥{{ total }}</span>
                        </div>
                        
                        <button class="checkout-btn" @click="checkout">
                            <i class="fas fa-credit-card"></i> 去结算
                        </button>
                    </div>
                </div>
            </div>
        </div>
        
        <footer>
            <div class="container">
                <p>Vue购物商城案例 &copy; 2023 - 仅供学习使用</p>
                <p>本案例使用Vue.js实现购物车功能，包含商品展示、筛选、添加/移除商品等</p>
            </div>
        </footer>
        
        <div class="notification" :class="{show: showNotification}">
            <i class="fas fa-check-circle"></i>
            {{ notificationMessage }}
        </div>
    </div>

    <script>
        new Vue({
            el: '#app',
            data: {
                showCart: true,
                activeFilter: 'all',
                notificationMessage: '',
                showNotification: false,
                products: [
                    {
                        id: 1,
                        name: '无线蓝牙耳机',
                        category: '电子产品',
                        price: 299,
                        discountPrice: 249,
                        onSale: true,
                        image: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8M3x8aGVhZHBob25lc3xlbnwwfHwwfHx8MA%3D%3D&auto=format&fit=crop&w=500&q=60'
                    },
                    {
                        id: 2,
                        name: '男士运动鞋',
                        category: '服装鞋帽',
                        price: 599,
                        discountPrice: 499,
                        onSale: true,
                        image: 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8Mnx8c25lYWtlcnN8ZW58MHx8MHx8fDA%3D&auto=format&fit=crop&w=500&q=60'
                    },
                    {
                        id: 3,
                        name: 'JavaScript高级程序设计',
                        category: '图书文娱',
                        price: 128,
                        discountPrice: 99,
                        onSale: true,
                        image: 'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8NXx8Ym9va3N8ZW58MHx8MHx8fDA%3D&auto=format&fit=crop&w=500&q=60'
                    },
                    {
                        id: 4,
                        name: '智能手表',
                        category: '电子产品',
                        price: 899,
                        discountPrice: null,
                        onSale: false,
                        image: 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8Mnx8c21hcnQlMjB3YXRjaHxlbnwwfHwwfHx8MA%3D%3D&auto=format&fit=crop&w=500&q=60'
                    },
                    {
                        id: 5,
                        name: '女士连衣裙',
                        category: '服装鞋帽',
                        price: 399,
                        discountPrice: null,
                        onSale: false,
                        image: 'https://images.unsplash.com/photo-1567095761054-7a02e69e5c43?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8NHx8ZHJlc3N8ZW58MHx8MHx8fDA%3D&auto=format&fit=crop&w=500&q=60'
                    },
                    {
                        id: 6,
                        name: 'Vue.js实战',
                        category: '图书文娱',
                        price: 79,
                        discountPrice: 59,
                        onSale: true,
                        image: 'https://images.unsplash.com/photo-1512820790803-83ca734da794?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8MTB8fGJvb2tzfGVufDB8fDB8fHww&auto=format&fit=crop&w=500&q=60'
                    },
                    {
                        id: 7,
                        name: '笔记本电脑',
                        category: '电子产品',
                        price: 5999,
                        discountPrice: 5499,
                        onSale: true,
                        image: 'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8Mnx8bGFwdG9wfGVufDB8fDB8fHww&auto=format&fit=crop&w=500&q=60'
                    },
                    {
                        id: 8,
                        name: '男士夹克',
                        category: '服装鞋帽',
                        price: 499,
                        discountPrice: null,
                        onSale: false,
                        image: 'https://images.unsplash.com/photo-1551028719-00167b16eac5?ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8Mnx8amFja2V0fGVufDB8fDB8fHww&auto=format&fit=crop&w=500&q=60'
                    }
                ],
                cart: []
            },
            computed: {
                filteredProducts() {
                    if (this.activeFilter === 'all') {
                        return this.products;
                    } else if (this.activeFilter === 'sale') {
                        return this.products.filter(product => product.onSale);
                    } else {
                        return this.products.filter(product => product.category === this.activeFilter);
                    }
                },
                cartItemCount() {
                    return this.cart.reduce((total, item) => total + item.quantity, 0);
                },
                subtotal() {
                    return this.cart.reduce((total, item) => {
                        const price = item.onSale ? item.discountPrice : item.price;
                        return total + (price * item.quantity);
                    }, 0);
                },
                totalSavings() {
                    return this.cart.reduce((total, item) => {
                        if (item.onSale) {
                            return total + ((item.price - item.discountPrice) * item.quantity);
                        }
                        return total;
                    }, 0);
                },
                total() {
                    return this.subtotal;
                }
            },
            methods: {
                toggleCart() {
                    this.showCart = !this.showCart;
                },
                isInCart(productId) {
                    return this.cart.some(item => item.id === productId);
                },
                addToCart(product) {
                    const existingItem = this.cart.find(item => item.id === product.id);
                    
                    if (existingItem) {
                        existingItem.quantity++;
                    } else {
                        this.cart.push({
                            ...product,
                            quantity: 1
                        });
                    }
                    
                    this.showNotificationMessage(`${product.name} 已添加到购物车`);
                },
                removeFromCart(item) {
                    const index = this.cart.findIndex(cartItem => cartItem.id === item.id);
                    if (index !== -1) {
                        this.cart.splice(index, 1);
                        this.showNotificationMessage(`${item.name} 已从购物车移除`);
                    }
                },
                increaseQuantity(item) {
                    item.quantity++;
                },
                decreaseQuantity(item) {
                    if (item.quantity > 1) {
                        item.quantity--;
                    } else {
                        this.removeFromCart(item);
                    }
                },
                checkout() {
                    if (this.cart.length === 0) {
                        this.showNotificationMessage('购物车是空的，无法结算');
                        return;
                    }
                    
                    const totalItems = this.cartItemCount;
                    const totalAmount = this.total;
                    this.showNotificationMessage(`结算成功！共${totalItems}件商品，总金额：¥${totalAmount}`);
                    
                    // 清空购物车
                    this.cart = [];
                },
                showNotificationMessage(message) {
                    this.notificationMessage = message;
                    this.showNotification = true;
                    
                    setTimeout(() => {
                        this.showNotification = false;
                    }, 3000);
                }
            },
            mounted() {
                // 初始化购物车
                this.cart = [
                    {
                        ...this.products[0],
                        quantity: 1
                    },
                    {
                        ...this.products[2],
                        quantity: 2
                    }
                ];
            }
        });
    </script>
</body>
</html>